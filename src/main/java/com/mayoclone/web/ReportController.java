package com.mayoclone.web;

import com.mayoclone.dto.ReportSummaryDto;
import com.mayoclone.dto.RiderPerformanceDto;
import com.mayoclone.service.ReportService;
import com.mayoclone.service.RiderPerformanceService;
import com.mayoclone.util.Csv;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/** Tenant-scoped reporting / analytics. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final RiderPerformanceService riderPerformanceService;

    public ReportController(ReportService reportService,
                           RiderPerformanceService riderPerformanceService) {
        this.reportService = reportService;
        this.riderPerformanceService = riderPerformanceService;
    }

    /** One-shot analytics summary over a delivery-date window (defaults to last 30 days). */
    @GetMapping("/summary")
    public ReportSummaryDto summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.summary(from, to);
    }

    /** The report's per-day rows as a CSV download (date,orders,revenue). */
    @GetMapping("/summary.csv")
    public ResponseEntity<String> summaryCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ReportSummaryDto s = reportService.summary(from, to);
        StringBuilder csv = new StringBuilder();
        csv.append(Csv.row(List.of("date", "orders", "revenue")));
        for (ReportSummaryDto.DayBucket d : s.byDay()) {
            csv.append(Csv.row(java.util.Arrays.asList(d.date(), d.orders(), d.revenue())));
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-summary.csv\"")
                .body(csv.toString());
    }

    /** Fulfilment/SLA metrics for every rider of the account over a window. */
    @GetMapping("/riders")
    public List<RiderPerformanceDto> riders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return riderPerformanceService.allPerformances(from, to);
    }
}
