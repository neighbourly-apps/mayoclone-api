package com.mayoclone.web;

import com.mayoclone.dto.IngestFailureDto;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.service.IngestFailureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Review queue (dead-letter) for mail we couldn't turn into an order. All routes
 * are authenticated and tenant-scoped to the caller's account.
 */
@RestController
@RequestMapping("/api/ingest-failures")
public class IngestFailureController {

    private final IngestFailureService service;

    public IngestFailureController(IngestFailureService service) {
        this.service = service;
    }

    @GetMapping
    public List<IngestFailureDto> list() {
        return service.list();
    }

    @PostMapping("/{id}/retry")
    public IngestResult retry(@PathVariable Long id) {
        return service.retry(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
