package com.mayoclone.service;

import com.mayoclone.domain.DashboardCredentialStatus;
import com.mayoclone.domain.Vendor;
import com.mayoclone.domain.VendorDashboardCredential;
import com.mayoclone.dto.DashboardCredentialDto;
import com.mayoclone.dto.UpsertDashboardCredentialRequest;
import com.mayoclone.repository.VendorDashboardCredentialRepository;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Tenant-scoped CRUD for a vendor's DASHBOARD login used by the order-enrichment
 * framework. Secrets are WRITE-ONLY: the {@code username}/{@code password} are accepted,
 * encrypted at rest (AES-256-GCM via the entity converter) and NEVER returned. The read
 * path exposes only {@link DashboardCredentialDto} (configured/status/expiry/lastError).
 *
 * <p>The owning {@code accountId} is always derived from the authenticated caller; a
 * cross-tenant (or unknown) vendor id returns 404.
 */
@Service
public class VendorDashboardCredentialService {

    private final VendorRepository vendorRepo;
    private final VendorDashboardCredentialRepository credRepo;
    private final CurrentAccountService currentAccount;
    private final AuditService auditService;

    public VendorDashboardCredentialService(VendorRepository vendorRepo,
                                            VendorDashboardCredentialRepository credRepo,
                                            CurrentAccountService currentAccount,
                                            AuditService auditService) {
        this.vendorRepo = vendorRepo;
        this.credRepo = credRepo;
        this.currentAccount = currentAccount;
        this.auditService = auditService;
    }

    /** Read the (secret-free) credential status for a vendor the caller owns. */
    @Transactional(readOnly = true)
    public DashboardCredentialDto get(Long vendorId) {
        Long accountId = currentAccount.accountId();
        requireVendor(vendorId, accountId);
        return credRepo.findByVendorIdAndAccountId(vendorId, accountId)
                .map(DashboardCredentialDto::from)
                .orElseGet(DashboardCredentialDto::notConfigured);
    }

    /**
     * Upsert the username+password for a vendor's dashboard login (encrypted at rest).
     * Changing the credentials resets the cached session and clears any prior error, and
     * moves the status back to {@code NEW} (so the enrich job will try again).
     */
    @Transactional
    public DashboardCredentialDto upsert(Long vendorId, UpsertDashboardCredentialRequest req) {
        Long accountId = currentAccount.accountId();
        requireVendor(vendorId, accountId);
        if (req == null || isBlank(req.username()) || isBlank(req.password())) {
            throw new ResponseStatusException(BAD_REQUEST, "username and password are required");
        }

        Instant now = Instant.now();
        VendorDashboardCredential cred = credRepo.findByVendorIdAndAccountId(vendorId, accountId)
                .orElseGet(() -> {
                    VendorDashboardCredential c = new VendorDashboardCredential();
                    c.setAccountId(accountId);
                    c.setVendorId(vendorId);
                    c.setProvider("IRCTC_ECATERING");
                    c.setCreatedAt(now);
                    return c;
                });
        cred.setUsername(req.username().trim());
        cred.setPassword(req.password());
        // New credentials → drop the stale session and re-arm the enrich flow.
        cred.setSessionToken(null);
        cred.setSessionExpiresAt(null);
        cred.setLastError(null);
        cred.setStatus(DashboardCredentialStatus.NEW);
        cred.setUpdatedAt(now);
        VendorDashboardCredential saved = credRepo.save(cred);

        auditService.record(accountId, null, "vendor.dashboard-credential.upsert", "vendor",
                String.valueOf(vendorId), Map.of("provider", saved.getProvider()));
        return DashboardCredentialDto.from(saved);
    }

    /** Delete a vendor's dashboard login (idempotent — a missing row is a no-op). */
    @Transactional
    public void delete(Long vendorId) {
        Long accountId = currentAccount.accountId();
        requireVendor(vendorId, accountId);
        Optional<VendorDashboardCredential> existing =
                credRepo.findByVendorIdAndAccountId(vendorId, accountId);
        existing.ifPresent(credRepo::delete);
        auditService.record(accountId, null, "vendor.dashboard-credential.delete", "vendor",
                String.valueOf(vendorId));
    }

    private Vendor requireVendor(Long vendorId, Long accountId) {
        return vendorRepo.findByIdAndAccountId(vendorId, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor " + vendorId + " not found"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
