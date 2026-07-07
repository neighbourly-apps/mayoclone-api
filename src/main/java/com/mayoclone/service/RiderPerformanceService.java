package com.mayoclone.service;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.Rider;
import com.mayoclone.dto.RiderPerformanceDto;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.RiderRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant-scoped rider performance / SLA over a delivery-date window (defaults to
 * the last 30 days). All reads are tenant-scoped; an unknown/cross-tenant rider id
 * surfaces as 404.
 */
@Service
public class RiderPerformanceService {

    private final IrctcOrderRepository orderRepo;
    private final RiderRepository riderRepo;
    private final CurrentAccountService currentAccount;

    public RiderPerformanceService(IrctcOrderRepository orderRepo,
                                   RiderRepository riderRepo,
                                   CurrentAccountService currentAccount) {
        this.orderRepo = orderRepo;
        this.riderRepo = riderRepo;
        this.currentAccount = currentAccount;
    }

    /** One rider's metrics. 404 if the rider is unknown or belongs to another tenant. */
    public RiderPerformanceDto performance(Long riderId, LocalDate from, LocalDate to) {
        Long accountId = currentAccount.accountId();
        Rider rider = riderRepo.findByIdAndAccountId(riderId, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Rider " + riderId + " not found"));
        LocalDate[] window = window(from, to);
        return compute(accountId, rider, window[0], window[1]);
    }

    /** Metrics for every rider of the account (including zero-activity riders). */
    public List<RiderPerformanceDto> allPerformances(LocalDate from, LocalDate to) {
        Long accountId = currentAccount.accountId();
        LocalDate[] window = window(from, to);
        return riderRepo.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(r -> compute(accountId, r, window[0], window[1]))
                .toList();
    }

    private RiderPerformanceDto compute(Long accountId, Rider rider, LocalDate from, LocalDate to) {
        List<IrctcOrder> orders = orderRepo
                .findByAccountIdAndRiderIdAndDeliveryDateBetween(accountId, rider.getId(), from, to);

        long assigned = orders.size();
        long delivered = 0;
        long undelivered = 0;
        long timedCount = 0;
        long totalMinutes = 0;

        for (IrctcOrder o : orders) {
            if (o.getStatus() == OrderStatus.DELIVERED) {
                delivered++;
                if (o.getAssignedAt() != null && o.getDeliveredAt() != null) {
                    timedCount++;
                    totalMinutes += Duration.between(o.getAssignedAt(), o.getDeliveredAt()).toMinutes();
                }
            } else if (o.getStatus() == OrderStatus.UNDELIVERED) {
                undelivered++;
            }
        }

        Double onTimeRate = (delivered + undelivered) == 0
                ? null
                : (double) delivered / (double) (delivered + undelivered);
        Double avgDeliveryMinutes = timedCount == 0
                ? null
                : (double) totalMinutes / (double) timedCount;

        return new RiderPerformanceDto(
                rider.getId(), rider.getName(), assigned, delivered, undelivered,
                onTimeRate, avgDeliveryMinutes);
    }

    /** Resolve the [from, to] window; defaults to the last 30 days inclusive. */
    private static LocalDate[] window(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : LocalDate.now();
        LocalDate f = from != null ? from : t.minusDays(29);
        return new LocalDate[]{f, t};
    }
}
