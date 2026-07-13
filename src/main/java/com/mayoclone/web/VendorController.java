package com.mayoclone.web;

import com.mayoclone.dto.CreateVendorRequest;
import com.mayoclone.dto.DashboardCredentialDto;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.UpsertDashboardCredentialRequest;
import com.mayoclone.dto.VendorDto;
import com.mayoclone.service.VendorDashboardCredentialService;
import com.mayoclone.service.VendorService;
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

/** Vendor signup + management. Passwords are never returned. */
@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorService vendorService;
    private final VendorDashboardCredentialService dashboardCredentialService;

    public VendorController(VendorService vendorService,
                            VendorDashboardCredentialService dashboardCredentialService) {
        this.vendorService = vendorService;
        this.dashboardCredentialService = dashboardCredentialService;
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

    // ---- Dashboard order-enrichment credential (secrets are write-only) ----

    /** Secret-free status of this vendor's dashboard login (configured/status/expiry/error). */
    @GetMapping("/{id}/dashboard-credential")
    public DashboardCredentialDto getDashboardCredential(@PathVariable Long id) {
        return dashboardCredentialService.get(id);
    }

    /** Upsert the vendor's dashboard username+password (encrypted at rest; never returned). */
    @PutMapping("/{id}/dashboard-credential")
    public DashboardCredentialDto putDashboardCredential(
            @PathVariable Long id,
            @Valid @RequestBody UpsertDashboardCredentialRequest request) {
        return dashboardCredentialService.upsert(id, request);
    }

    /** Remove the vendor's dashboard credential. */
    @DeleteMapping("/{id}/dashboard-credential")
    public ResponseEntity<Void> deleteDashboardCredential(@PathVariable Long id) {
        dashboardCredentialService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
