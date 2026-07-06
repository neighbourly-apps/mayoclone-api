package com.mayoclone.web;

import com.mayoclone.domain.OrderStatus;
import com.mayoclone.domain.OrderType;
import com.mayoclone.domain.PaymentMode;
import com.mayoclone.dto.AssignRiderRequest;
import com.mayoclone.dto.CreateDirectOrderRequest;
import com.mayoclone.dto.InvoiceDto;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderStatsDto;
import com.mayoclone.dto.UpdateStatusRequest;
import com.mayoclone.service.OrderCommandService;
import com.mayoclone.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderCommandService orderCommandService;

    public OrderController(OrderService orderService, OrderCommandService orderCommandService) {
        this.orderService = orderService;
        this.orderCommandService = orderCommandService;
    }

    /**
     * List orders newest first. All filters optional and combinable:
     * {@code aggregatorCode}, {@code station} (delivery station code),
     * {@code date} (delivery date), {@code trainNumber}, {@code status},
     * {@code orderType}, {@code paymentMode}, {@code riderId}.
     */
    @GetMapping
    public List<OrderDto> list(
            @RequestParam(required = false) String aggregatorCode,
            @RequestParam(required = false) String station,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String trainNumber,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderType orderType,
            @RequestParam(required = false) PaymentMode paymentMode,
            @RequestParam(required = false) Long riderId) {
        return orderService.list(aggregatorCode, station, date, trainNumber,
                status, orderType, paymentMode, riderId);
    }

    @GetMapping("/stats")
    public OrderStatsDto stats() {
        return orderService.stats();
    }

    /** Create a manual DIRECT (walk-in/phone) order. */
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto createDirect(@Valid @RequestBody CreateDirectOrderRequest req) {
        return orderCommandService.createDirect(req);
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @GetMapping("/{id}/invoice")
    public InvoiceDto invoice(@PathVariable Long id) {
        return orderService.invoice(id);
    }

    /** Set/clear the assigned rider ({@code riderId: null} clears it). */
    @PatchMapping("/{id}/assign")
    public OrderDto assign(@PathVariable Long id, @RequestBody AssignRiderRequest req) {
        return orderCommandService.assign(id, req);
    }

    /** Apply a lifecycle status transition (illegal transition → 409). */
    @PatchMapping("/{id}/status")
    public OrderDto status(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        return orderCommandService.changeStatus(id, req);
    }
}
