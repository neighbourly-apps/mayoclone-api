package com.mayoclone.service;

import com.mayoclone.billing.BillingProperties;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionPayment;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.dto.AdminAccountDetailDto;
import com.mayoclone.dto.AdminAccountDto;
import com.mayoclone.dto.AdminStatsDto;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.SubscriptionPaymentRepository;
import com.mayoclone.repository.VendorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-tenant, platform-operator (SUPER_ADMIN) service. Unlike every other service
 * it is NOT tenant-scoped: it deliberately reads across all accounts. Access is
 * gated to {@code hasRole("SUPER_ADMIN")} at the {@code /api/admin/**} route level.
 *
 * <p>Mutations (extend-trial / activate / suspend) are audit-logged and return the
 * refreshed {@link AdminAccountDto}.
 */
@Service
public class AdminService {

    private final AccountRepository accountRepo;
    private final SubscriptionPaymentRepository paymentRepo;
    private final IrctcOrderRepository orderRepo;
    private final VendorRepository vendorRepo;
    private final BillingProperties billingProps;
    private final AuditService auditService;

    public AdminService(AccountRepository accountRepo,
                        SubscriptionPaymentRepository paymentRepo,
                        IrctcOrderRepository orderRepo,
                        VendorRepository vendorRepo,
                        BillingProperties billingProps,
                        AuditService auditService) {
        this.accountRepo = accountRepo;
        this.paymentRepo = paymentRepo;
        this.orderRepo = orderRepo;
        this.vendorRepo = vendorRepo;
        this.billingProps = billingProps;
        this.auditService = auditService;
    }

    // ---- Stats -------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminStatsDto stats() {
        String sa = Account.ROLE_SUPER_ADMIN;
        long total = accountRepo.countByRoleNot(sa);
        long trialing = accountRepo.countByRoleNotAndSubscriptionStatus(sa, SubscriptionStatus.TRIALING);
        long active = accountRepo.countByRoleNotAndSubscriptionStatus(sa, SubscriptionStatus.ACTIVE);
        long expired = accountRepo.countByRoleNotAndSubscriptionStatus(sa, SubscriptionStatus.EXPIRED);
        long cancelled = accountRepo.countByRoleNotAndSubscriptionStatus(sa, SubscriptionStatus.CANCELLED);
        long newLast7 = accountRepo.countByRoleNotAndCreatedAtAfter(sa, Instant.now().minus(7, ChronoUnit.DAYS));
        long totalRevenue = paymentRepo.totalRevenuePaise();
        long paying = paymentRepo.payingAccountCount();
        // MRR estimate = number of ACTIVE (paid) tenant accounts × the plan's monthly
        // amount. We use the ACTIVE count rather than everPaid so churned/cancelled
        // accounts don't count toward recurring revenue.
        long mrr = active * billingProps.getPlanAmount();
        return new AdminStatsDto(total, trialing, active, expired, cancelled,
                newLast7, totalRevenue, paying, mrr);
    }

    // ---- List --------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminAccountDto> listAccounts(String q, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        SubscriptionStatus statusFilter = parseStatus(status);

        Page<Account> accounts = accountRepo.adminSearch(like, statusFilter, pageable);
        List<Long> ids = accounts.getContent().stream().map(Account::getId).toList();

        // Batched rollups — three aggregate queries total instead of N+1 per row.
        Map<Long, Long> orderCounts = toCountMap(orderRepo.countByAccountIds(ids));
        Map<Long, Long> vendorCounts = toCountMap(vendorRepo.countByAccountIds(ids));
        Map<Long, long[]> payAgg = new HashMap<>();
        Map<Long, Instant> lastPay = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : paymentRepo.aggregateByAccountIds(ids)) {
                Long accId = (Long) row[0];
                Instant maxAt = (Instant) row[2];
                long sum = ((Number) row[3]).longValue();
                payAgg.put(accId, new long[]{sum});
                lastPay.put(accId, maxAt);
            }
        }

        Instant now = Instant.now();
        return accounts.map(a -> toDto(a, now,
                orderCounts.getOrDefault(a.getId(), 0L),
                vendorCounts.getOrDefault(a.getId(), 0L),
                lastPay.get(a.getId()),
                payAgg.containsKey(a.getId()) ? payAgg.get(a.getId())[0] : 0L));
    }

    // ---- Detail ------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminAccountDetailDto getAccount(Long id) {
        Account a = accountRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
        Instant now = Instant.now();

        List<SubscriptionPayment> payments = paymentRepo.findByAccountIdOrderByCreatedAtDesc(id);
        long totalPaid = payments.stream().mapToLong(SubscriptionPayment::getAmount).sum();
        Instant lastPaymentAt = payments.isEmpty() ? null : payments.get(0).getCreatedAt();

        AdminAccountDto dto = toDto(a, now,
                orderRepo.countByAccountId(id),
                vendorRepo.countByAccountId(id),
                lastPaymentAt,
                totalPaid);

        List<AdminAccountDetailDto.Payment> paymentDtos = payments.stream()
                .map(p -> new AdminAccountDetailDto.Payment(
                        p.getAmount(), p.getCurrency(), p.getProvider(),
                        p.getProviderPaymentId(), p.getCreatedAt()))
                .toList();

        List<AdminAccountDetailDto.Mailbox> mailboxDtos = vendorRepo.findByAccountId(id).stream()
                .map(v -> new AdminAccountDetailDto.Mailbox(
                        v.getId(),
                        v.getSourceType() == null ? null : v.getSourceType().name(),
                        v.getOwnerEmail(),
                        v.getOauthEmail(),
                        v.getImapHost(),
                        v.isActive(),
                        v.getLastSyncedAt()))
                .toList();

        return new AdminAccountDetailDto(dto, paymentDtos, mailboxDtos);
    }

    // ---- Mutations ---------------------------------------------------------

    @Transactional
    public AdminAccountDto extendTrial(Long id, int days, String actorEmail) {
        int d = requirePositiveDays(days);
        Account a = load(id);
        Instant now = Instant.now();
        Instant base = a.getTrialEndsAt() != null && a.getTrialEndsAt().isAfter(now) ? a.getTrialEndsAt() : now;
        a.setSubscriptionStatus(SubscriptionStatus.TRIALING);
        a.setTrialEndsAt(base.plus(d, ChronoUnit.DAYS));
        accountRepo.save(a);
        audit(a, actorEmail, "admin.extend_trial", Map.of("days", d, "trialEndsAt", a.getTrialEndsAt().toString()));
        return refresh(a);
    }

    @Transactional
    public AdminAccountDto activate(Long id, int days, String actorEmail) {
        int d = requirePositiveDays(days);
        Account a = load(id);
        Instant now = Instant.now();
        Instant base = a.getCurrentPeriodEnd() != null && a.getCurrentPeriodEnd().isAfter(now)
                ? a.getCurrentPeriodEnd() : now;
        a.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        a.setCurrentPeriodEnd(base.plus(d, ChronoUnit.DAYS));
        accountRepo.save(a);
        audit(a, actorEmail, "admin.activate", Map.of("days", d, "currentPeriodEnd", a.getCurrentPeriodEnd().toString()));
        return refresh(a);
    }

    @Transactional
    public AdminAccountDto suspend(Long id, String actorEmail, Long actorId) {
        if (actorId != null && actorId.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a super-admin cannot suspend itself");
        }
        Account a = load(id);
        if (Account.ROLE_SUPER_ADMIN.equals(a.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot suspend a platform super-admin");
        }
        a.setSubscriptionStatus(SubscriptionStatus.CANCELLED);
        accountRepo.save(a);
        audit(a, actorEmail, "admin.suspend", Map.of());
        return refresh(a);
    }

    // ---- Helpers -----------------------------------------------------------

    private Account load(Long id) {
        return accountRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
    }

    private AdminAccountDto refresh(Account a) {
        Instant now = Instant.now();
        List<SubscriptionPayment> payments = paymentRepo.findByAccountIdOrderByCreatedAtDesc(a.getId());
        long totalPaid = payments.stream().mapToLong(SubscriptionPayment::getAmount).sum();
        Instant lastPaymentAt = payments.isEmpty() ? null : payments.get(0).getCreatedAt();
        return toDto(a, now,
                orderRepo.countByAccountId(a.getId()),
                vendorRepo.countByAccountId(a.getId()),
                lastPaymentAt, totalPaid);
    }

    private void audit(Account a, String actorEmail, String action, Map<String, ?> details) {
        auditService.record(a.getId(), actorEmail, action, "account", String.valueOf(a.getId()), details);
    }

    private static AdminAccountDto toDto(Account a, Instant now, long orderCount, long vendorCount,
                                         Instant lastPaymentAt, long totalPaidPaise) {
        return new AdminAccountDto(
                a.getId(),
                a.getEmail(),
                a.getBusinessName(),
                a.getRole(),
                a.getSubscriptionStatus() == null ? null : a.getSubscriptionStatus().name(),
                a.isSubscriptionActive(now),
                a.getTrialEndsAt(),
                a.getCurrentPeriodEnd(),
                a.getPlan(),
                a.getCreatedAt(),
                orderCount,
                vendorCount,
                lastPaymentAt,
                totalPaidPaise);
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return map;
    }

    private static SubscriptionStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SubscriptionStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + status);
        }
    }

    private static int requirePositiveDays(int days) {
        if (days <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be a positive integer");
        }
        return days;
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 25;
        }
        return Math.min(size, 200);
    }
}
