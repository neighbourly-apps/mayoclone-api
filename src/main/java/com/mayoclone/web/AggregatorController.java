package com.mayoclone.web;

import com.mayoclone.dto.AggregatorDto;
import com.mayoclone.dto.CreateAggregatorRequest;
import com.mayoclone.service.AggregatorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** CRUD over the managed aggregator table (seeded on startup, editable here). */
@RestController
@RequestMapping("/api/aggregators")
public class AggregatorController {

    private final AggregatorService aggregatorService;

    public AggregatorController(AggregatorService aggregatorService) {
        this.aggregatorService = aggregatorService;
    }

    @GetMapping
    public List<AggregatorDto> list() {
        return aggregatorService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AggregatorDto create(@Valid @RequestBody CreateAggregatorRequest request) {
        return aggregatorService.create(request);
    }

    @PutMapping("/{id}")
    public AggregatorDto update(@PathVariable Long id, @Valid @RequestBody CreateAggregatorRequest request) {
        return aggregatorService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        aggregatorService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
