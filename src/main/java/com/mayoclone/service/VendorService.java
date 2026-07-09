package com.mayoclone.service;

import com.mayoclone.domain.MailSourceType;
import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.CreateVendorRequest;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.VendorDto;
import com.mayoclone.ingest.gmail.GmailWatchService;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Vendor (mailbox) signup/CRUD + per-vendor mailbox sync — all scoped to the
 * caller's tenant. The owning {@code accountId} is always derived from the
 * authenticated caller, never from the request body. Cross-tenant ids return 404.
 *
 * <p>FORWARDING vendors are minted a unique {@code ingestAddress} at creation so
 * the vendor can forward their aggregator mail to it (returned in the DTO).
 */
@Service
public class VendorService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VendorRepository vendorRepo;
    private final ImapIngestionService ingestionService;
    private final CurrentAccountService currentAccount;
    private final AuditService auditService;
    private final GmailWatchService gmailWatchService;
    private final String inboundDomain;

    public VendorService(VendorRepository vendorRepo, ImapIngestionService ingestionService,
                         CurrentAccountService currentAccount, AuditService auditService,
                         GmailWatchService gmailWatchService,
                         @Value("${mayoclone.inbound.domain:inbound.mayoclone.local}") String inboundDomain) {
        this.vendorRepo = vendorRepo;
        this.ingestionService = ingestionService;
        this.currentAccount = currentAccount;
        this.auditService = auditService;
        this.gmailWatchService = gmailWatchService;
        this.inboundDomain = inboundDomain;
    }

    public List<VendorDto> list() {
        return vendorRepo.findByAccountId(currentAccount.accountId()).stream()
                .map(VendorDto::from).toList();
    }

    public VendorDto create(CreateVendorRequest req) {
        MailSourceType sourceType = req.sourceType() == null ? MailSourceType.IMAP : req.sourceType();

        Vendor v = new Vendor();
        v.setAccountId(currentAccount.accountId()); // tenant from the token, never the body
        v.setSourceType(sourceType);
        v.setRestaurantName(req.restaurantName());
        v.setOwnerEmail(req.ownerEmail());
        v.setStationCode(req.stationCode());
        v.setStationName(req.stationName());
        v.setPhone(req.phone());
        v.setGstin(req.gstin());
        v.setAddressLine(req.addressLine());

        switch (sourceType) {
            case IMAP -> configureImap(v, req);
            case FORWARDING -> v.setIngestAddress(mintIngestAddress());
            case GMAIL_OAUTH -> throw new ResponseStatusException(BAD_REQUEST,
                    "Gmail vendors are created via the Gmail OAuth connect flow, not this endpoint");
        }

        v.setUseSsl(req.useSsl() == null || req.useSsl()); // default true
        v.setActive(true);
        v.setCreatedAt(Instant.now());
        Vendor saved = vendorRepo.save(v);

        auditService.record(saved.getAccountId(), null, "vendor.create", "vendor",
                String.valueOf(saved.getId()), Map.of("sourceType", sourceType.name()));
        return VendorDto.from(saved);
    }

    private void configureImap(Vendor v, CreateVendorRequest req) {
        if (req.imapHost() == null || req.imapHost().isBlank()
                || req.imapPassword() == null || req.imapPassword().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "imapHost and imapPassword are required for an IMAP vendor");
        }
        v.setImapHost(req.imapHost());
        v.setImapPort(req.imapPort() == null ? 993 : req.imapPort());
        String username = req.imapUsername();
        v.setImapUsername(username == null || username.isBlank() ? req.ownerEmail() : username);
        v.setImapPassword(req.imapPassword());
    }

    /** Mint a collision-checked {@code <token>@<inboundDomain>} address (lowercase). */
    private String mintIngestAddress() {
        for (int i = 0; i < 5; i++) {
            byte[] bytes = new byte[12];
            RANDOM.nextBytes(bytes);
            String token = java.util.HexFormat.of().formatHex(bytes);
            String address = token + "@" + inboundDomain.toLowerCase();
            if (!vendorRepo.existsByIngestAddress(address)) {
                return address;
            }
        }
        throw new ResponseStatusException(BAD_REQUEST, "Could not mint a unique ingest address");
    }

    public void delete(Long id) {
        Long accountId = currentAccount.accountId();
        Vendor vendor = vendorRepo.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor " + id + " not found"));
        // Best-effort: deregister the Gmail watch BEFORE the row (and its refresh
        // token) is deleted. Never blocks the delete.
        if (vendor.getSourceType() == MailSourceType.GMAIL_OAUTH) {
            gmailWatchService.stopWatch(vendor);
        }
        vendorRepo.deleteById(id);
        auditService.record(accountId, null, "vendor.delete", "vendor", String.valueOf(id));
    }

    public IngestResult sync(Long id) {
        Vendor v = vendorRepo.findByIdAndAccountId(id, currentAccount.accountId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor " + id + " not found"));
        return ingestionService.syncVendor(v);
    }
}
