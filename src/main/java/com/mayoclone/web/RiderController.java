package com.mayoclone.web;

import com.mayoclone.dto.CreateRiderRequest;
import com.mayoclone.dto.RiderDto;
import com.mayoclone.dto.RiderPerformanceDto;
import com.mayoclone.dto.UpdateRiderRequest;
import com.mayoclone.service.RiderPerformanceService;
import com.mayoclone.service.RiderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/riders")
public class RiderController {

    private final RiderService riderService;
    private final RiderPerformanceService riderPerformanceService;

    public RiderController(RiderService riderService,
                          RiderPerformanceService riderPerformanceService) {
        this.riderService = riderService;
        this.riderPerformanceService = riderPerformanceService;
    }

    @GetMapping
    public List<RiderDto> list() {
        return riderService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RiderDto create(@Valid @RequestBody CreateRiderRequest req) {
        return riderService.create(req);
    }

    @PatchMapping("/{id}")
    public RiderDto update(@PathVariable Long id, @Valid @RequestBody UpdateRiderRequest req) {
        return riderService.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        riderService.delete(id);
    }

    /** Fulfilment/SLA metrics for one rider over a delivery-date window (default last 30 days). */
    @GetMapping("/{id}/performance")
    public RiderPerformanceDto performance(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return riderPerformanceService.performance(id, from, to);
    }
}
