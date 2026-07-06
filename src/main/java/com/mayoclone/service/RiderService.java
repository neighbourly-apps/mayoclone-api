package com.mayoclone.service;

import com.mayoclone.domain.Rider;
import com.mayoclone.dto.CreateRiderRequest;
import com.mayoclone.dto.RiderDto;
import com.mayoclone.dto.UpdateRiderRequest;
import com.mayoclone.repository.RiderRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Tenant-scoped CRUD for delivery riders. Cross-tenant ids surface as 404. */
@Service
public class RiderService {

    private final RiderRepository riderRepo;
    private final CurrentAccountService currentAccount;

    public RiderService(RiderRepository riderRepo, CurrentAccountService currentAccount) {
        this.riderRepo = riderRepo;
        this.currentAccount = currentAccount;
    }

    public List<RiderDto> list() {
        return riderRepo.findByAccountIdOrderByCreatedAtDesc(currentAccount.accountId()).stream()
                .map(RiderDto::from)
                .toList();
    }

    @Transactional
    public RiderDto create(CreateRiderRequest req) {
        Rider r = new Rider();
        r.setAccountId(currentAccount.accountId());
        r.setName(req.name());
        r.setPhone(req.phone());
        r.setActive(true);
        r.setCreatedAt(Instant.now());
        return RiderDto.from(riderRepo.save(r));
    }

    @Transactional
    public RiderDto update(Long id, UpdateRiderRequest req) {
        Rider r = find(id);
        if (req.name() != null) {
            r.setName(req.name());
        }
        if (req.phone() != null) {
            r.setPhone(req.phone());
        }
        if (req.active() != null) {
            r.setActive(req.active());
        }
        return RiderDto.from(riderRepo.save(r));
    }

    @Transactional
    public void delete(Long id) {
        riderRepo.delete(find(id));
    }

    private Rider find(Long id) {
        return riderRepo.findByIdAndAccountId(id, currentAccount.accountId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Rider " + id + " not found"));
    }
}
