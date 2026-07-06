package com.mayoclone.web;

import com.mayoclone.domain.Aggregator;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderStatsDto;
import com.mayoclone.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** List orders, newest first; optional ?aggregator=ZOMATO filter. */
    @GetMapping
    public List<OrderDto> list(@RequestParam(required = false) Aggregator aggregator) {
        return orderService.list(aggregator);
    }

    @GetMapping("/stats")
    public OrderStatsDto stats() {
        return orderService.stats();
    }
}
