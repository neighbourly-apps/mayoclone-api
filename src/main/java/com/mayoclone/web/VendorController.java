package com.mayoclone.web;

import com.mayoclone.dto.CreateVendorRequest;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.VendorDto;
import com.mayoclone.service.VendorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Vendor signup + management. Passwords are never returned. */
@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendorService;

    public VendorController(VendorService vendorService) {
        this.vendorService = vendorService;
    }

    @GetMapping
    public List<VendorDto> list() {
        return vendorService.list();
    }

    /** Vendor signup: shares their mailbox so we can scrape aggregator orders. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VendorDto create(@Valid @RequestBody CreateVendorRequest request) {
        return vendorService.create(request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vendorService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Trigger an on-demand IMAP sync for a single vendor. */
    @PostMapping("/{id}/sync")
    public IngestResult sync(@PathVariable Long id) {
        return vendorService.sync(id);
    }
}
