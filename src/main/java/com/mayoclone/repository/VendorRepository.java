package com.mayoclone.repository;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VendorRepository extends JpaRepository<Vendor, Long> {

    /** Global sweep across all tenants — used ONLY by the manual/legacy inline sync path. */
    List<Vendor> findByActiveTrue();

    // ---- Mailbox poll dispatcher: "due, pollable" vendors across all tenants ----

    /**
     * Active vendors due for a mailbox sync, oldest-due first. Backs the
     * {@code MailboxPollDispatcher}: {@code next_poll_at IS NULL} (never polled) sorts
     * first, FORWARDING (push-only) is always excluded, and GMAIL_OAUTH is excluded
     * unless {@code gmailPollable} (i.e. Gmail push is NOT handling those mailboxes).
     * Indexed by {@code (active, source_type, next_poll_at)} so this stays cheap at 2000+.
     */
    @Query("select v from Vendor v "
            + "where v.active = true "
            + "and (v.nextPollAt is null or v.nextPollAt <= :now) "
            + "and v.sourceType <> com.mayoclone.domain.MailSourceType.FORWARDING "
            + "and (:gmailPollable = true or v.sourceType <> com.mayoclone.domain.MailSourceType.GMAIL_OAUTH) "
            + "order by v.nextPollAt asc nulls first")
    List<Vendor> findDuePollable(@Param("now") Instant now,
                                 @Param("gmailPollable") boolean gmailPollable,
                                 Pageable pageable);

    /** Cheap count of due, pollable vendors — feeds the backlog gauge. */
    @Query("select count(v) from Vendor v "
            + "where v.active = true "
            + "and (v.nextPollAt is null or v.nextPollAt <= :now) "
            + "and v.sourceType <> com.mayoclone.domain.MailSourceType.FORWARDING "
            + "and (:gmailPollable = true or v.sourceType <> com.mayoclone.domain.MailSourceType.GMAIL_OAUTH)")
    long countDuePollable(@Param("now") Instant now, @Param("gmailPollable") boolean gmailPollable);

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

    // ---- Gmail push: resolve/renew connected mailboxes (background paths) ----

    /**
     * Resolve a connected Gmail mailbox by its Google account email — used by the
     * Pub/Sub push endpoint. Global (not account-scoped) because the push notification
     * only carries the mailbox email, and a given Gmail address maps to one vendor.
     */
    Optional<Vendor> findFirstBySourceTypeAndOauthEmail(MailSourceType sourceType, String oauthEmail);

    /** Active vendors of a given source type — used by the watch renewer sweep. */
    List<Vendor> findByActiveTrueAndSourceType(MailSourceType sourceType);
}
