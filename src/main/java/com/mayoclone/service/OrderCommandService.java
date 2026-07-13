package com.mayoclone.service;

import com.mayoclone.domain.IrctcOrder;
import com.mayoclone.domain.OrderItem;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderStatusEvent;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.Rider;
import com.mayoclone.dto.AssignRiderRequest;
import com.mayoclone.dto.CreateDirectOrderRequest;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.UpdateStatusRequest;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.repository.OrderStatusEventRepository;
import com.mayoclone.repository.RiderRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Write-side order operations: manual (DIRECT) order creation, rider assignment,
 * and lifecycle status transitions. Every path is tenant-scoped via
 * {@link CurrentAccountService}; cross-tenant ids surface as 404. Each mutation
 * appends an {@link OrderStatusEvent} and pushes a realtime event.
 */
@Service
public class OrderCommandService {

    private final IrctcOrderRepository orderRepo;
    private final RiderRepository riderRepo;
    private final OrderStatusEventRepository eventRepo;
    private final CurrentAccountService currentAccount;
    private final OrderMapper mapper;
    private final OrderEvents events;

    public OrderCommandService(IrctcOrderRepository orderRepo,
                               RiderRepository riderRepo,
                               OrderStatusEventRepository eventRepo,
                               CurrentAccountService currentAccount,
                               OrderMapper mapper,
                               OrderEvents events) {
        this.orderRepo = orderRepo;
        this.riderRepo = riderRepo;
        this.eventRepo = eventRepo;
        this.currentAccount = currentAccount;
        this.mapper = mapper;
        this.events = events;
    }

    /** Create a manual walk-in/phone order (no aggregator). Emits NEW_ORDER. */
    @Transactional
    public OrderDto createDirect(CreateDirectOrderRequest req) {
        Long accountId = currentAccount.accountId();
        Instant now = Instant.now();

        IrctcOrder o = new IrctcOrder();
        o.setAccountId(accountId);
        o.setAggregator(null);
        o.setVendorId(null);
        o.setOrderType(OrderType.DIRECT);
        o.setPaymentMode(req.paymentMode());
        o.setStatus(OrderStatus.NEW);
        o.setTrainNumber(req.trainNumber());
        o.setTrainName(req.trainName());
        o.setPnr(req.pnr());
        o.setCoach(req.coach());
        o.setBerth(req.berth());
        o.setDeliveryStationCode(req.deliveryStationCode());
        o.setDeliveryStationName(req.deliveryStationName());
        o.setPassengerName(req.passengerName());
        o.setPassengerPhone(req.passengerPhone());
        o.setDeliveryDate(req.deliveryDate());
        o.setDeliverySlot(req.deliverySlot());
        o.setCurrency("INR");
        o.setItems(req.items().stream()
                .map(i -> new OrderItem(i.name(), i.qty(), i.price()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new)));
        o.setAmount(req.amount() != null ? req.amount() : sumItems(req));
        o.setPlacedAt(now);
        o.setCreatedAt(now);

        IrctcOrder saved = orderRepo.save(o);
        // Generate a stable DIR-<seq> reference now that we have the id.
        saved.setExternalOrderId("DIR-" + saved.getId());
        saved = orderRepo.save(saved);

        recordCreated(saved);
        return mapper.toDto(saved);
    }

    /** Set or clear ({@code riderId == null}) the assigned rider. Emits ASSIGNED. */
    @Transactional
    public OrderDto assign(Long orderId, AssignRiderRequest req) {
        Long accountId = currentAccount.accountId();
        IrctcOrder o = find(orderId, accountId);

        if (req == null || req.riderId() == null) {
            o.setRiderId(null);
            o.setAssignedAt(null);
        } else {
            Rider rider = riderRepo.findByIdAndAccountId(req.riderId(), accountId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Rider " + req.riderId() + " not found"));
            o.setRiderId(rider.getId());
            o.setAssignedAt(Instant.now());
            // Convenience auto-advance: an ACCEPTED order goes OUT_FOR_DELIVERY when a rider picks it up.
            if (o.getStatus() == OrderStatus.ACCEPTED) {
                o.setStatus(OrderStatus.OUT_FOR_DELIVERY);
                eventRepo.save(new OrderStatusEvent(o.getId(), OrderStatus.OUT_FOR_DELIVERY,
                        Instant.now(), "auto-advanced on rider assignment"));
            }
        }

        IrctcOrder saved = orderRepo.save(o);
        OrderDto dto = mapper.toDto(saved);
        events.assigned(accountId, dto);
        return dto;
    }

    /** Apply a validated lifecycle transition. Illegal transitions → 409. Emits STATUS_CHANGED. */
    @Transactional
    public OrderDto changeStatus(Long orderId, UpdateStatusRequest req) {
        Long accountId = currentAccount.accountId();
        IrctcOrder o = find(orderId, accountId);

        OrderStatus from = o.getStatus();
        OrderStatus to = req.status();
        if (!from.canTransitionTo(to)) {
            throw new ResponseStatusException(CONFLICT, "Illegal status transition " + from + " -> " + to);
        }

        o.setStatus(to);
        // BILL_PENDING is the "delivered, bill pending" point — stamp deliveredAt here.
        if (to == OrderStatus.BILL_PENDING) {
            o.setDeliveredAt(Instant.now());
        }
        // Capture the cancellation reason on the order (and prefer it as the event
        // note) when moving to CANCELLED.
        String eventNote = req.note();
        if (to == OrderStatus.CANCELLED && req.cancellationReason() != null) {
            o.setCancellationReason(req.cancellationReason());
            if (eventNote == null) {
                eventNote = req.cancellationReason();
            }
        }
        eventRepo.save(new OrderStatusEvent(o.getId(), to, Instant.now(), eventNote));

        IrctcOrder saved = orderRepo.save(o);
        OrderDto dto = mapper.toDto(saved);
        events.statusChanged(accountId, dto);
        return dto;
    }

    /**
     * Record the initial status event for a freshly-created order and push a
     * NEW_ORDER event. Called by both the direct-create path and the ingestion
     * pipeline (which has no request-bound account, so the tenant is read off the
     * saved order).
     */
    @Transactional
    public void recordCreated(IrctcOrder saved) {
        if (saved == null || saved.getId() == null) {
            return;
        }
        eventRepo.save(new OrderStatusEvent(saved.getId(), saved.getStatus(), Instant.now(), "created"));
        events.newOrder(saved.getAccountId(), mapper.toDto(saved));
    }

    /**
     * Push a realtime ENRICHED event for an order that was just back-filled from the
     * vendor dashboard (see the dashboard order-enrichment framework). Mirrors
     * {@link #recordCreated} but does NOT append a status event — the lifecycle state
     * is unchanged; only descriptive fields (e.g. the passenger name) were filled.
     * The tenant is read off the saved order (no request-bound account in the job path).
     */
    @Transactional
    public void pushEnriched(IrctcOrder saved) {
        if (saved == null || saved.getId() == null) {
            return;
        }
        events.enriched(saved.getAccountId(), mapper.toDto(saved));
    }

    private BigDecimal sumItems(CreateDirectOrderRequest req) {
        return req.items().stream()
                .map(i -> (i.price() != null ? i.price() : BigDecimal.ZERO)
                        .multiply(BigDecimal.valueOf(i.qty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private IrctcOrder find(Long id, Long accountId) {
        return orderRepo.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Order " + id + " not found"));
    }
}
