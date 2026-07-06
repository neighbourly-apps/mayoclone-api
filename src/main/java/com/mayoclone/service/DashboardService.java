package com.mayoclone.service;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.dto.DailyDashboardDto;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the tenant-scoped daily-business dashboard for a delivery date. */
@Service
public class DashboardService {

    private final IrctcOrderRepository orderRepo;
    private final CurrentAccountService currentAccount;

    public DashboardService(IrctcOrderRepository orderRepo, CurrentAccountService currentAccount) {
        this.orderRepo = orderRepo;
        this.currentAccount = currentAccount;
    }

    public DailyDashboardDto daily(LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        Long accountId = currentAccount.accountId();

        // Only this tenant's rows for this delivery date (uses the account+date index).
        List<IrctcOrder> orders = orderRepo.findByAccountIdAndDeliveryDate(accountId, day);

        long onlineCount = 0;
        long codCount = 0;
        BigDecimal onlineRevenue = BigDecimal.ZERO;
        BigDecimal codRevenue = BigDecimal.ZERO;
        long undelivered = 0;
        long unassigned = 0;

        // Seed every status at 0 so the map is complete.
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        // Preserve a stable aggregator ordering as encountered.
        Map<String, DailyDashboardDto.AggregatorCount> byAgg = new LinkedHashMap<>();

        for (IrctcOrder o : orders) {
            BigDecimal amount = o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO;
            if (o.getPaymentMode() == PaymentMode.COD) {
                codCount++;
                codRevenue = codRevenue.add(amount);
            } else {
                onlineCount++;
                onlineRevenue = onlineRevenue.add(amount);
            }

            byStatus.merge(o.getStatus().name(), 1L, Long::sum);

            if (o.getStatus() == OrderStatus.UNDELIVERED) {
                undelivered++;
            }
            if (o.getRiderId() == null) {
                unassigned++;
            }
            if (o.getAggregator() != null) {
                String code = o.getAggregator().getCode();
                DailyDashboardDto.AggregatorCount cur = byAgg.get(code);
                long next = (cur == null ? 0L : cur.count()) + 1L;
                byAgg.put(code, new DailyDashboardDto.AggregatorCount(code, o.getAggregator().getName(), next));
            }
        }

        List<DailyDashboardDto.AggregatorCount> byAggregator = new ArrayList<>(byAgg.values());

        return new DailyDashboardDto(
                day,
                orders.size(),
                new DailyDashboardDto.Bucket(onlineCount, onlineRevenue),
                new DailyDashboardDto.Bucket(codCount, codRevenue),
                byStatus,
                undelivered,
                unassigned,
                byAggregator
        );
    }
}
