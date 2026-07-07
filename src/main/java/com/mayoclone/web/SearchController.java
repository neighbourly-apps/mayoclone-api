package com.mayoclone.web;

import com.mayoclone.dto.SearchResponse;
import com.mayoclone.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped global order search. */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final OrderService orderService;

    public SearchController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Case-insensitive match of {@code q} against externalOrderId, pnr,
     * passengerPhone, passengerName, trainNumber, deliveryStationCode. Newest
     * first. {@code limit} defaults to 20, capped at 50.
     */
    @GetMapping
    public SearchResponse search(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "20") int limit) {
        return orderService.search(q, limit);
    }
}
