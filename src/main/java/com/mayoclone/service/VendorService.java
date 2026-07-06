package com.mayoclone.service;

import com.mayoclone.domain.Vendor;
import com.mayoclone.dto.CreateVendorRequest;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.dto.VendorDto;
import com.mayoclone.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Vendor signup/CRUD + per-vendor mailbox sync. */
@Service
public class VendorService {

    private final VendorRepository vendorRepo;
    private final ImapIngestionService ingestionService;

    public VendorService(VendorRepository vendorRepo, ImapIngestionService ingestionService) {
        this.vendorRepo = vendorRepo;
        this.ingestionService = ingestionService;
    }

    public List<VendorDto> list() {
        return vendorRepo.findAll().stream().map(VendorDto::from).toList();
    }

    public VendorDto create(CreateVendorRequest req) {
        Vendor v = new Vendor();
        v.setRestaurantName(req.restaurantName());
        v.setOwnerEmail(req.ownerEmail());
        v.setStationCode(req.stationCode());
        v.setStationName(req.stationName());
        v.setPhone(req.phone());
        v.setGstin(req.gstin());
        v.setAddressLine(req.addressLine());
        v.setImapHost(req.imapHost());
        v.setImapPort(req.imapPort() == null ? 993 : req.imapPort());
        // Default the IMAP username to the shared mailbox when left blank.
        String username = req.imapUsername();
        v.setImapUsername(username == null || username.isBlank() ? req.ownerEmail() : username);
        v.setImapPassword(req.imapPassword());
        v.setUseSsl(req.useSsl() == null || req.useSsl()); // default true
        v.setActive(true);
        v.setCreatedAt(Instant.now());
        return VendorDto.from(vendorRepo.save(v));
    }

    public void delete(Long id) {
        if (!vendorRepo.existsById(id)) {
            throw new ResponseStatusException(NOT_FOUND, "Vendor " + id + " not found");
        }
        vendorRepo.deleteById(id);
    }

    public IngestResult sync(Long id) {
        Vendor v = vendorRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Vendor " + id + " not found"));
        return ingestionService.syncVendor(v);
    }
}
