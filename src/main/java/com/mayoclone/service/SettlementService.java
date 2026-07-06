package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.Settlement;
import com.mayoclone.dto.CreateSettlementRequest;
import com.mayoclone.dto.SettlementDto;
import com.mayoclone.dto.SettlementSummaryDto;
import com.mayoclone.dto.UpdateSettlementRequest;
import com.mayoclone.repository.AggregatorRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.SettlementRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Settlement / reconciliation over ONLINE (aggregator-attributed) orders.
 *
 * <p>The window is applied on {@code deliveryDate} (the natural settlement date),
 * inclusive on both ends. CANCELLED orders and DIRECT/COD-only reconciliation are
 * out of scope here: only ONLINE orders with a non-null aggregator that are NOT
 * CANCELLED contribute.
 *
 * <ul>
 *   <li>commissionAmount = grossAmount * commissionRate (rounded HALF_UP to 2dp)</li>
 *   <li>netPayable = grossAmount - commissionAmount</li>
 * </ul>
 */
@Service
public class SettlementService {

    private final IrctcOrderRepository orderRepo;
    private final AggregatorRepository aggregatorRepo;
    private final SettlementRepository settlementRepo;
    private final CurrentAccountService currentAccount;

    public SettlementService(IrctcOrderRepository orderRepo,
                             AggregatorRepository aggregatorRepo,
                             SettlementRepository settlementRepo,
                             CurrentAccountService currentAccount) {
        this.orderRepo = orderRepo;
        this.aggregatorRepo = aggregatorRepo;
        this.settlementRepo = settlementRepo;
        this.currentAccount = currentAccount;
    }

    // --------------------------------------------------------------- summary

    public SettlementSummaryDto summary(LocalDate from, LocalDate to) {
        Long accountId = currentAccount.accountId();
        LocalDate f = from != null ? from : LocalDate.now().minusDays(29);
        LocalDate t = to != null ? to : LocalDate.now();

        Map<Long, Accumulator> byAgg = accumulate(accountId, f, t);

        List<SettlementSummaryDto.Row> rows = new ArrayList<>();
        long totOrders = 0;
        BigDecimal totGross = BigDecimal.ZERO;
        BigDecimal totCommission = BigDecimal.ZERO;
        BigDecimal totNet = BigDecimal.ZERO;

        for (Accumulator a : byAgg.values()) {
            BigDecimal gross = money(a.gross);
            BigDecimal commission = money(gross.multiply(a.rate));
            BigDecimal net = money(gross.subtract(commission));
            rows.add(new SettlementSummaryDto.Row(
                    a.aggregatorId, a.code, a.name, a.orders, gross, a.rate, commission, net));
            totOrders += a.orders;
            totGross = totGross.add(gross);
            totCommission = totCommission.add(commission);
            totNet = totNet.add(net);
        }

        return new SettlementSummaryDto(f, t, rows,
                new SettlementSummaryDto.Totals(totOrders, totGross, totCommission, totNet));
    }

    // --------------------------------------------------------------- persist

    @Transactional
    public SettlementDto create(CreateSettlementRequest req) {
        Long accountId = currentAccount.accountId();
        Aggregator agg = aggregatorRepo.findByCodeIgnoreCase(req.aggregatorCode())
                .orElseThrow(() -> new ResponseStatusException(
                        NOT_FOUND, "Aggregator '" + req.aggregatorCode() + "' not found"));
        if (req.to().isBefore(req.from())) {
            throw new ResponseStatusException(BAD_REQUEST, "'to' must not be before 'from'");
        }

        Map<Long, Accumulator> byAgg = accumulate(accountId, req.from(), req.to());
        Accumulator a = byAgg.get(agg.getId());

        long orders = a != null ? a.orders : 0L;
        BigDecimal gross = money(a != null ? a.gross : BigDecimal.ZERO);
        BigDecimal rate = agg.getCommissionRate() != null ? agg.getCommissionRate() : BigDecimal.ZERO;
        BigDecimal commission = money(gross.multiply(rate));
        BigDecimal net = money(gross.subtract(commission));

        Settlement s = new Settlement();
        s.setAccountId(accountId);
        s.setAggregatorId(agg.getId());
        s.setAggregatorCode(agg.getCode());
        s.setAggregatorName(agg.getName());
        s.setPeriodStart(req.from());
        s.setPeriodEnd(req.to());
        s.setOrders(orders);
        s.setGrossAmount(gross);
        s.setCommissionRate(rate);
        s.setCommissionAmount(commission);
        s.setNetPayable(net);
        s.setStatus(Settlement.STATUS_PENDING);
        s.setCreatedAt(Instant.now());
        return SettlementDto.from(settlementRepo.save(s));
    }

    public List<SettlementDto> list() {
        return settlementRepo.findByAccountIdOrderByCreatedAtDesc(currentAccount.accountId()).stream()
                .map(SettlementDto::from)
                .toList();
    }

    @Transactional
    public SettlementDto update(Long id, UpdateSettlementRequest req) {
        Settlement s = settlementRepo.findByIdAndAccountId(id, currentAccount.accountId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Settlement " + id + " not found"));

        if (req.status() != null) {
            String status = req.status();
            if (!Settlement.STATUS_PENDING.equals(status) && !Settlement.STATUS_SETTLED.equals(status)) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "status must be one of PENDING, SETTLED");
            }
            s.setStatus(status);
            if (Settlement.STATUS_SETTLED.equals(status)) {
                if (s.getSettledAt() == null) {
                    s.setSettledAt(Instant.now());
                }
            } else {
                s.setSettledAt(null);
            }
        }
        if (req.note() != null) {
            s.setNote(req.note());
        }
        return SettlementDto.from(settlementRepo.save(s));
    }

    // --------------------------------------------------------------- helpers

    /** Group the eligible ONLINE, non-cancelled orders in range by aggregator. */
    private Map<Long, Accumulator> accumulate(Long accountId, LocalDate from, LocalDate to) {
        List<IrctcOrder> orders = orderRepo.findByAccountIdAndDeliveryDateBetween(accountId, from, to);
        Map<Long, Accumulator> byAgg = new LinkedHashMap<>();
        for (IrctcOrder o : orders) {
            if (o.getOrderType() != OrderType.ONLINE) {
                continue;
            }
            Aggregator agg = o.getAggregator();
            if (agg == null) {
                continue; // DIRECT-attributed / null aggregator: not reconcilable
            }
            if (o.getStatus() == OrderStatus.CANCELLED) {
                continue;
            }
            BigDecimal amount = o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO;
            Accumulator a = byAgg.computeIfAbsent(agg.getId(), k -> new Accumulator(
                    agg.getId(), agg.getCode(), agg.getName(),
                    agg.getCommissionRate() != null ? agg.getCommissionRate() : BigDecimal.ZERO));
            a.orders++;
            a.gross = a.gross.add(amount);
        }
        return byAgg;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Accumulator {
        final Long aggregatorId;
        final String code;
        final String name;
        final BigDecimal rate;
        long orders;
        BigDecimal gross = BigDecimal.ZERO;

        Accumulator(Long aggregatorId, String code, String name, BigDecimal rate) {
            this.aggregatorId = aggregatorId;
            this.code = code;
            this.name = name;
            this.rate = rate;
        }
    }
}
