package com.mayoclone.repository;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    /** Global sweep across all tenants — used ONLY by the background PollScheduler. */
    List<Vendor> findByActiveTrue();

    // ---- Tenant-scoped access (all request-driven paths MUST use these) ----

    List<Vendor> findByAccountId(Long accountId);

    List<Vendor> findByAccountIdAndActiveTrue(Long accountId);

    Optional<Vendor> findByIdAndAccountId(Long id, Long accountId);

    boolean existsByIdAndAccountId(Long id, Long accountId);

    // ---- Inbound webhook: resolve a FORWARDING vendor by its minted address ----

    Optional<Vendor> findByIngestAddress(String ingestAddress);

    boolean existsByIngestAddress(String ingestAddress);

    // ---- Gmail OAuth: locate/attach the connected mailbox for an account ----

    Optional<Vendor> findByAccountIdAndSourceTypeAndOauthEmail(
            Long accountId, MailSourceType sourceType, String oauthEmail);
}
