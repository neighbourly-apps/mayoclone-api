package com.mayoclone.web;

import com.mayoclone.dto.InvoiceDto;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderStatsDto;
import com.mayoclone.service.OrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * List orders newest first. All filters optional and combinable:
     * {@code aggregatorCode}, {@code station} (delivery station code),
     * {@code date} (delivery date), {@code trainNumber}.
     */
    @GetMapping
    public List<OrderDto> list(
            @RequestParam(required = false) String aggregatorCode,
            @RequestParam(required = false) String station,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String trainNumber) {
        return orderService.list(aggregatorCode, station, date, trainNumber);
    }

    @GetMapping("/stats")
    public OrderStatsDto stats() {
        return orderService.stats();
    }

    @GetMapping("/{id}")
    public OrderDto get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @GetMapping("/{id}/invoice")
    public InvoiceDto invoice(@PathVariable Long id) {
        return orderService.invoice(id);
    }
}
