package com.mayoclone.repository;

import com.mayoclone.domain.VendorDashboardCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VendorDashboardCredentialRepository
        extends JpaRepository<VendorDashboardCredential, Long> {

    /** Background enrichment path (the job resolves the vendor's login by vendor id). */
    Optional<VendorDashboardCredential> findByVendorId(Long vendorId);

    /** Tenant-scoped access — every request-driven path MUST use this. */
    Optional<VendorDashboardCredential> findByVendorIdAndAccountId(Long vendorId, Long accountId);
}
