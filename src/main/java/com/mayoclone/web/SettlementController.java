package com.mayoclone.web;

import com.mayoclone.dto.CreateSettlementRequest;
import com.mayoclone.dto.SettlementDto;
import com.mayoclone.dto.SettlementSummaryDto;
import com.mayoclone.dto.UpdateSettlementRequest;
import com.mayoclone.service.SettlementService;
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

/** Tenant-scoped settlement / reconciliation endpoints. */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** Computed (not persisted) per-aggregator reconciliation over the window. */
    @GetMapping("/summary")
    public SettlementSummaryDto summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return settlementService.summary(from, to);
    }

    @GetMapping
    public List<SettlementDto> list() {
        return settlementService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementDto create(@Valid @RequestBody CreateSettlementRequest req) {
        return settlementService.create(req);
    }

    @PatchMapping("/{id}")
    public SettlementDto update(@PathVariable Long id, @Valid @RequestBody UpdateSettlementRequest req) {
        return settlementService.update(id, req);
    }
}
