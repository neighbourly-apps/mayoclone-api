package com.mayoclone.web;

import com.mayoclone.dto.CreateSettlementRequest;
import com.mayoclone.dto.SettlementDto;
import com.mayoclone.dto.SettlementSummaryDto;
import com.mayoclone.dto.UpdateSettlementRequest;
import com.mayoclone.service.SettlementService;
import com.mayoclone.util.Csv;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /** The per-aggregator settlement summary as a CSV download (rows + a TOTAL row). */
    @GetMapping("/summary.csv")
    public ResponseEntity<String> summaryCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SettlementSummaryDto s = settlementService.summary(from, to);
        StringBuilder csv = new StringBuilder();
        csv.append(Csv.row(List.of(
                "aggregatorId", "aggregatorCode", "name", "orders",
                "grossAmount", "commissionRate", "commissionAmount", "netPayable")));
        for (SettlementSummaryDto.Row r : s.rows()) {
            csv.append(Csv.row(java.util.Arrays.asList(
                    r.aggregatorId(), r.aggregatorCode(), r.name(), r.orders(),
                    r.grossAmount(), r.commissionRate(), r.commissionAmount(), r.netPayable())));
        }
        SettlementSummaryDto.Totals t = s.totals();
        csv.append(Csv.row(java.util.Arrays.asList(
                "", "TOTAL", "", t.orders(), t.grossAmount(), "", t.commissionAmount(), t.netPayable())));
        return csvResponse(csv.toString(), "settlement-summary.csv");
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

    static ResponseEntity<String> csvResponse(String body, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
