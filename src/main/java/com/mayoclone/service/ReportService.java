package com.mayoclone.service;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.dto.ReportSummaryDto;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped reporting / analytics over a delivery-date window.
 *
 * <p>The window is applied on {@code deliveryDate}, inclusive on both ends,
 * defaulting to the last 30 days. Order COUNTS include every status; monetary
 * REVENUE everywhere EXCLUDES CANCELLED orders, and {@code topItems} is built only
 * from non-cancelled orders.
 */
@Service
public class ReportService {

    private static final String DIRECT_CODE = "DIRECT";
    private static final String DIRECT_NAME = "Direct";

    private final IrctcOrderRepository orderRepo;
    private final CurrentAccountService currentAccount;

    public ReportService(IrctcOrderRepository orderRepo, CurrentAccountService currentAccount) {
        this.orderRepo = orderRepo;
        this.currentAccount = currentAccount;
    }

    public ReportSummaryDto summary(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from : t.minusDays(29); // last 30 days inclusive
        Long accountId = currentAccount.accountId();

        List<IrctcOrder> orders = orderRepo.findByAccountIdAndDeliveryDateBetween(accountId, f, t);

        long totalOrders = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        long onlineCount = 0;
        BigDecimal onlineRevenue = BigDecimal.ZERO;
        long codCount = 0;
        BigDecimal codRevenue = BigDecimal.ZERO;

        long delivered = 0;
        long undelivered = 0;

        long deliveredWithTimes = 0;
        long totalDeliveryMinutes = 0;

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.values()) {
            statusCounts.put(s.name(), 0L);
        }

        Map<String, AggAcc> byAgg = new LinkedHashMap<>();
        Map<String, StationAcc> byStation = new LinkedHashMap<>();
        Map<LocalDate, DayAcc> byDay = new LinkedHashMap<>();
        // 24 zero-filled hour buckets; bucketed on placedAt hour in the server zone.
        HourAcc[] byHour = new HourAcc[24];
        for (int h = 0; h < 24; h++) {
            byHour[h] = new HourAcc(h);
        }
        Map<String, TrainAcc> byTrain = new LinkedHashMap<>();
        Map<String, ItemAcc> byItem = new LinkedHashMap<>();

        for (IrctcOrder o : orders) {
            boolean cancelled = o.getStatus() == OrderStatus.CANCELLED;
            BigDecimal amount = o.getAmount() != null ? o.getAmount() : BigDecimal.ZERO;
            BigDecimal rev = cancelled ? BigDecimal.ZERO : amount;

            totalOrders++;
            totalRevenue = totalRevenue.add(rev);

            if (o.getPaymentMode() == PaymentMode.COD) {
                codCount++;
                codRevenue = codRevenue.add(rev);
            } else {
                onlineCount++;
                onlineRevenue = onlineRevenue.add(rev);
            }

            statusCounts.merge(o.getStatus().name(), 1L, Long::sum);
            if (o.getStatus() == OrderStatus.DELIVERED) {
                delivered++;
            } else if (o.getStatus() == OrderStatus.UNDELIVERED) {
                undelivered++;
            }

            if (o.getStatus() == OrderStatus.DELIVERED
                    && o.getPlacedAt() != null && o.getDeliveredAt() != null) {
                long minutes = Duration.between(o.getPlacedAt(), o.getDeliveredAt()).toMinutes();
                deliveredWithTimes++;
                totalDeliveryMinutes += minutes;
            }

            // by aggregator (DIRECT bucket for null aggregator)
            Aggregator agg = o.getAggregator();
            String aggCode = agg != null ? agg.getCode() : DIRECT_CODE;
            String aggName = agg != null ? agg.getName() : DIRECT_NAME;
            AggAcc aa = byAgg.computeIfAbsent(aggCode, k -> new AggAcc(k, aggName));
            aa.orders++;
            aa.revenue = aa.revenue.add(rev);

            // by station
            if (o.getDeliveryStationCode() != null) {
                StationAcc sa = byStation.computeIfAbsent(o.getDeliveryStationCode(),
                        k -> new StationAcc(k, o.getDeliveryStationName()));
                sa.orders++;
                sa.revenue = sa.revenue.add(rev);
            }

            // by train
            if (o.getTrainNumber() != null) {
                TrainAcc ta = byTrain.computeIfAbsent(o.getTrainNumber(),
                        k -> new TrainAcc(k, o.getTrainName()));
                ta.orders++;
            }

            // by day (bucketed on deliveryDate)
            if (o.getDeliveryDate() != null) {
                DayAcc da = byDay.computeIfAbsent(o.getDeliveryDate(), DayAcc::new);
                da.orders++;
                da.revenue = da.revenue.add(rev);
            }

            // by hour (bucketed on placedAt hour, server zone; non-cancelled only)
            if (!cancelled && o.getPlacedAt() != null) {
                int hour = o.getPlacedAt().atZone(java.time.ZoneId.systemDefault()).getHour();
                HourAcc ha = byHour[hour];
                ha.orders++;
                ha.revenue = ha.revenue.add(rev);
            }

            // top items (non-cancelled only)
            if (!cancelled) {
                for (OrderItem it : o.getItems()) {
                    if (it.getName() == null) {
                        continue;
                    }
                    ItemAcc ia = byItem.computeIfAbsent(it.getName(), ItemAcc::new);
                    ia.qty += it.getQty();
                    BigDecimal price = it.getPrice() != null ? it.getPrice() : BigDecimal.ZERO;
                    ia.revenue = ia.revenue.add(price.multiply(BigDecimal.valueOf(it.getQty())));
                }
            }
        }

        double fulfillmentRate = (delivered + undelivered) == 0
                ? 0.0
                : (double) delivered / (double) (delivered + undelivered);

        Double avgDeliveryMinutes = deliveredWithTimes == 0
                ? null
                : (double) totalDeliveryMinutes / (double) deliveredWithTimes;

        // byAggregator: most orders first
        List<ReportSummaryDto.AggregatorBucket> aggregatorBuckets = byAgg.values().stream()
                .sorted(Comparator.comparingLong((AggAcc a) -> a.orders).reversed()
                        .thenComparing(a -> a.code))
                .map(a -> new ReportSummaryDto.AggregatorBucket(a.code, a.name, a.orders, a.revenue))
                .toList();

        // byStation: most orders first
        List<ReportSummaryDto.StationBucket> stationBuckets = byStation.values().stream()
                .sorted(Comparator.comparingLong((StationAcc a) -> a.orders).reversed()
                        .thenComparing(a -> a.code))
                .map(a -> new ReportSummaryDto.StationBucket(a.code, a.name, a.orders, a.revenue))
                .toList();

        // byDay: every day in range, zero-filled, chronological
        List<ReportSummaryDto.DayBucket> dayBuckets = new ArrayList<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) {
            DayAcc da = byDay.get(d);
            dayBuckets.add(new ReportSummaryDto.DayBucket(
                    d,
                    da != null ? da.orders : 0L,
                    da != null ? da.revenue : BigDecimal.ZERO));
        }

        // byHour: 24 entries, zero-filled, hour 0..23 ascending
        List<ReportSummaryDto.HourBucket> hourBuckets = new ArrayList<>(24);
        for (HourAcc ha : byHour) {
            hourBuckets.add(new ReportSummaryDto.HourBucket(ha.hour, ha.orders, ha.revenue));
        }

        // topTrains: top 10 by order count
        List<ReportSummaryDto.TrainBucket> topTrains = byTrain.values().stream()
                .sorted(Comparator.comparingLong((TrainAcc a) -> a.orders).reversed()
                        .thenComparing(a -> a.trainNumber))
                .limit(10)
                .map(a -> new ReportSummaryDto.TrainBucket(a.trainNumber, a.trainName, a.orders))
                .toList();

        // topItems: top 10 by qty
        List<ReportSummaryDto.ItemBucket> topItems = byItem.values().stream()
                .sorted(Comparator.comparingLong((ItemAcc a) -> a.qty).reversed()
                        .thenComparing(a -> a.name))
                .limit(10)
                .map(a -> new ReportSummaryDto.ItemBucket(a.name, a.qty, a.revenue))
                .toList();

        return new ReportSummaryDto(
                f, t,
                new ReportSummaryDto.Totals(totalOrders, totalRevenue),
                new ReportSummaryDto.PaymentSplit(
                        new ReportSummaryDto.Bucket(onlineCount, onlineRevenue),
                        new ReportSummaryDto.Bucket(codCount, codRevenue)),
                statusCounts,
                fulfillmentRate,
                avgDeliveryMinutes,
                aggregatorBuckets,
                stationBuckets,
                dayBuckets,
                hourBuckets,
                topTrains,
                topItems);
    }

    // ------------------------------------------------------------ accumulators

    private static final class AggAcc {
        final String code;
        final String name;
        long orders;
        BigDecimal revenue = BigDecimal.ZERO;

        AggAcc(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final class StationAcc {
        final String code;
        final String name;
        long orders;
        BigDecimal revenue = BigDecimal.ZERO;

        StationAcc(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final class DayAcc {
        final LocalDate date;
        long orders;
        BigDecimal revenue = BigDecimal.ZERO;

        DayAcc(LocalDate date) {
            this.date = date;
        }
    }

    private static final class HourAcc {
        final int hour;
        long orders;
        BigDecimal revenue = BigDecimal.ZERO;

        HourAcc(int hour) {
            this.hour = hour;
        }
    }

    private static final class TrainAcc {
        final String trainNumber;
        final String trainName;
        long orders;

        TrainAcc(String trainNumber, String trainName) {
            this.trainNumber = trainNumber;
            this.trainName = trainName;
        }
    }

    private static final class ItemAcc {
        final String name;
        long qty;
        BigDecimal revenue = BigDecimal.ZERO;

        ItemAcc(String name) {
            this.name = name;
        }
    }
}
