package com.mayoclone.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.billing.BillingDtos.BillingStatus;
import com.mayoclone.billing.BillingDtos.CheckoutResponse;
import com.mayoclone.billing.BillingDtos.PlanDto;
import com.mayoclone.domain.Account;
import com.mayoclone.domain.SubscriptionPayment;
import com.mayoclone.domain.SubscriptionStatus;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.SubscriptionPaymentRepository;
import com.mayoclone.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Owns the trial + paid-subscription lifecycle: reporting status, creating Razorpay
 * checkout orders, verifying payments (client callback + server webhook), and the
 * dev-mode shortcut. All money is in the currency's minor unit (paise for INR).
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    public static final String PROVIDER_RAZORPAY = "razorpay";
    public static final String PROVIDER_DEV = "dev";

    private final AccountRepository accountRepo;
    private final SubscriptionPaymentRepository paymentRepo;
    private final BillingProperties props;
    private final RazorpayClient razorpay;
    private final AuditService auditService;
    private final ObjectMapper json;

    public BillingService(AccountRepository accountRepo,
                          SubscriptionPaymentRepository paymentRepo,
                          BillingProperties props,
                          RazorpayClient razorpay,
                          AuditService auditService,
                          ObjectMapper json) {
        this.accountRepo = accountRepo;
        this.paymentRepo = paymentRepo;
        this.props = props;
        this.razorpay = razorpay;
        this.auditService = auditService;
        this.json = json;
    }

    // --- status ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public BillingStatus status(Long accountId) {
        return statusFor(load(accountId));
    }

    private BillingStatus statusFor(Account a) {
        Instant now = Instant.now();
        boolean active = a.isSubscriptionActive(now);
        int trialDaysLeft = 0;
        if (a.getSubscriptionStatus() == SubscriptionStatus.TRIALING && a.getTrialEndsAt() != null) {
            long minutes = Duration.between(now, a.getTrialEndsAt()).toMinutes();
            long days = (long) Math.ceil(minutes / (60.0 * 24.0));
            trialDaysLeft = (int) Math.max(0, days);
        }
        return new BillingStatus(
                a.getSubscriptionStatus().name(),
                a.getTrialEndsAt(),
                a.getCurrentPeriodEnd(),
                active,
                trialDaysLeft,
                props.planOrDefault(a.getPlan()),   // account's CURRENT plan (default when unset)
                List.copyOf(props.plans()),          // all purchasable plans (monthly first)
                props.isRazorpayEnabled(),
                props.isDevMode());
    }

    // --- invoices (customer-facing payment history) --------------------------

    /** The signed-in account's own payments, newest first, as displayable invoices. */
    @Transactional(readOnly = true)
    public List<BillingDtos.InvoiceDto> invoices(Long accountId) {
        return paymentRepo.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(p -> new BillingDtos.InvoiceDto(
                        "INV-" + String.format("%06d", p.getId()),
                        p.getCreatedAt(),
                        p.getAmount(),
                        p.getCurrency(),
                        p.getProvider(),
                        p.getProviderPaymentId(),
                        describe(p.getAmount())))
                .toList();
    }

    /** Best-effort plan name for an amount (matches a configured plan); generic otherwise. */
    private String describe(long amount) {
        return props.plans().stream()
                .filter(pl -> pl.amount() == amount)
                .map(pl -> pl.name() + " subscription")
                .findFirst()
                .orElse("Subscription");
    }

    // --- checkout -------------------------------------------------------------

    @Transactional(readOnly = true)
    public CheckoutResponse checkout(Long accountId, String planCode) {
        if (!props.isRazorpayEnabled()) {
            throw new BillingException(HttpStatus.SERVICE_UNAVAILABLE, "billing_not_configured");
        }
        PlanDto plan = props.plan(planCode); // 400 on unknown code
        Account a = load(accountId);
        String receipt = "acct-" + a.getId() + "-" + Instant.now().getEpochSecond();
        // The plan code rides along in the order notes so the SERVER webhook can learn
        // which plan was paid; the client /verify carries it in its request body.
        String orderId = razorpay.createOrder(
                props.getRazorpayKeyId(), props.getRazorpayKeySecret(),
                plan.amount(), plan.currency(), receipt,
                Map.of("accountId", String.valueOf(a.getId()), "plan", plan.code()));
        return new CheckoutResponse(props.getRazorpayKeyId(), orderId,
                plan.amount(), plan.currency(), plan.name(), plan.code());
    }

    // --- verify (client callback) --------------------------------------------

    @Transactional
    public BillingStatus verify(Long accountId, String orderId, String paymentId, String signature, String planCode) {
        if (orderId == null || paymentId == null || signature == null
                || props.getRazorpayKeySecret().isBlank()
                || !RazorpaySignature.verify(props.getRazorpayKeySecret(),
                orderId + "|" + paymentId, signature)) {
            throw new BillingException(HttpStatus.BAD_REQUEST, "invalid_signature");
        }
        PlanDto plan = props.plan(planCode); // 400 on unknown code
        Account a = load(accountId);
        extendPeriod(a, PROVIDER_RAZORPAY, paymentId, plan);
        auditService.record(a.getId(), a.getEmail(), "billing.verify", "account",
                String.valueOf(a.getId()), Map.of("paymentId", paymentId, "plan", plan.code()));
        return statusFor(a);
    }

    // --- webhook (server-to-server) ------------------------------------------

    /** Acks (200) captured/paid events by extending the account period; throws 401 on a bad signature. */
    @Transactional
    public void webhook(String rawBody, String signatureHeader) {
        if (props.getRazorpayWebhookSecret().isBlank()
                || !RazorpaySignature.verify(props.getRazorpayWebhookSecret(), rawBody, signatureHeader)) {
            throw new BillingException(HttpStatus.UNAUTHORIZED, "invalid_signature");
        }
        Map<String, Object> root;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            root = parsed;
        } catch (Exception e) {
            log.warn("Razorpay webhook: unparseable body");
            return; // 200 ack — nothing actionable
        }
        String event = str(root.get("event"));
        if (!"payment.captured".equals(event) && !"order.paid".equals(event)) {
            return; // ack unrelated events
        }
        Map<String, Object> payment = entity(root, "payment");
        Map<String, Object> order = entity(root, "order");
        String paymentId = payment == null ? null : str(payment.get("id"));
        Long acctId = resolveAccountId(payment, order);
        if (paymentId == null || acctId == null) {
            log.warn("Razorpay webhook {}: could not resolve payment/account", event);
            return;
        }
        // Plan the customer paid for: from the order/payment notes we set at checkout,
        // falling back to the account's current plan, then the default.
        PlanDto plan = resolvePlanFromNotes(payment, order);
        long amount = payment.get("amount") == null ? plan.amount() : asLong(payment.get("amount"));
        String currency = payment.get("currency") == null ? plan.currency() : str(payment.get("currency"));
        Account a = accountRepo.findById(acctId).orElse(null);
        if (a == null) {
            log.warn("Razorpay webhook: account {} not found", acctId);
            return;
        }
        if (plan == null) {
            plan = props.planOrDefault(a.getPlan());
        }
        extendPeriod(a, PROVIDER_RAZORPAY, paymentId, plan, amount, currency);
        auditService.record(a.getId(), a.getEmail(), "billing.webhook", "account",
                String.valueOf(a.getId()), Map.of("event", event, "paymentId", paymentId, "plan", plan.code()));
    }

    // --- dev-activate ---------------------------------------------------------

    @Transactional
    public BillingStatus devActivate(Long accountId, String planCode) {
        if (!props.isDevMode()) {
            throw new BillingException(HttpStatus.NOT_FOUND, "not_found");
        }
        PlanDto plan = props.plan(planCode); // 400 on unknown code
        Account a = load(accountId);
        String paymentId = "dev-" + a.getId() + "-" + Instant.now().toEpochMilli();
        extendPeriod(a, PROVIDER_DEV, paymentId, plan);
        auditService.record(a.getId(), a.getEmail(), "billing.dev_activate", "account",
                String.valueOf(a.getId()), Map.of("plan", plan.code()));
        return statusFor(a);
    }

    // --- shared ---------------------------------------------------------------

    private void extendPeriod(Account a, String provider, String paymentId, PlanDto plan) {
        extendPeriod(a, provider, paymentId, plan, plan.amount(), plan.currency());
    }

    /**
     * Idempotent on (provider, paymentId): sets the account's plan to {@code plan},
     * extends the paid period by that plan's window (from max(now, currentPeriodEnd)),
     * marks ACTIVE, and RESETS the renewal-reminder marker so the next period reminds.
     */
    private void extendPeriod(Account a, String provider, String paymentId, PlanDto plan, long amount, String currency) {
        if (paymentRepo.existsByProviderAndProviderPaymentId(provider, paymentId)) {
            return; // already processed
        }
        Instant now = Instant.now();
        Instant base = (a.getCurrentPeriodEnd() != null && a.getCurrentPeriodEnd().isAfter(now))
                ? a.getCurrentPeriodEnd() : now;
        a.setCurrentPeriodEnd(base.plus(plan.periodDays(), ChronoUnit.DAYS));
        a.setSubscriptionStatus(SubscriptionStatus.ACTIVE);
        a.setPlan(plan.code());
        a.setRenewalReminderSentAt(null); // new period → allow a fresh renewal reminder
        accountRepo.save(a);
        paymentRepo.save(new SubscriptionPayment(a.getId(), provider, paymentId, amount, currency, now));
    }

    /** Reads a {@code plan} note off the payment/order entity; null when absent/unknown. */
    @SuppressWarnings("unchecked")
    private PlanDto resolvePlanFromNotes(Map<String, Object> payment, Map<String, Object> order) {
        for (Map<String, Object> src : new Map[]{payment, order}) {
            if (src == null) {
                continue;
            }
            Object notes = src.get("notes");
            if (notes instanceof Map<?, ?> m) {
                Object code = ((Map<String, Object>) m).get("plan");
                if (code != null && !String.valueOf(code).isBlank()) {
                    try {
                        return props.plan(String.valueOf(code));
                    } catch (BillingException ignored) {
                        // unknown note value — fall through to caller's default
                    }
                }
            }
        }
        return null;
    }

    private Account load(Long accountId) {
        return accountRepo.findById(accountId)
                .orElseThrow(() -> new BillingException(HttpStatus.UNAUTHORIZED, "account_not_found"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entity(Map<String, Object> root, String key) {
        Object payload = root.get("payload");
        if (!(payload instanceof Map<?, ?> p)) {
            return null;
        }
        Object node = ((Map<String, Object>) p).get(key);
        if (!(node instanceof Map<?, ?> n)) {
            return null;
        }
        Object entity = ((Map<String, Object>) n).get("entity");
        return entity instanceof Map<?, ?> e ? (Map<String, Object>) e : null;
    }

    @SuppressWarnings("unchecked")
    private static Long resolveAccountId(Map<String, Object> payment, Map<String, Object> order) {
        for (Map<String, Object> src : new Map[]{payment, order}) {
            if (src == null) {
                continue;
            }
            Object notes = src.get("notes");
            if (notes instanceof Map<?, ?> m) {
                Long id = parseLong(((Map<String, Object>) m).get("accountId"));
                if (id != null) {
                    return id;
                }
            }
            Long fromReceipt = accountFromReceipt(str(src.get("receipt")));
            if (fromReceipt != null) {
                return fromReceipt;
            }
        }
        return null;
    }

    /** receipt "acct-<id>-<ts>" → id. */
    private static Long accountFromReceipt(String receipt) {
        if (receipt == null || !receipt.startsWith("acct-")) {
            return null;
        }
        String[] parts = receipt.split("-");
        return parts.length >= 2 ? parseLong(parts[1]) : null;
    }

    private static Long parseLong(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long asLong(Object o) {
        Long v = parseLong(o);
        return v == null ? 0L : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
