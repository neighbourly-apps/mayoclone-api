package com.mayoclone.service;

import com.mayoclone.domain.MenuItem;
import com.mayoclone.dto.CreateMenuItemRequest;
import com.mayoclone.dto.MenuItemDto;
import com.mayoclone.dto.UpdateMenuItemRequest;
import com.mayoclone.repository.MenuItemRepository;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Tenant-scoped CRUD for the menu catalog. Cross-tenant ids surface as 404. */
@Service
public class MenuItemService {

    private final MenuItemRepository repo;
    private final CurrentAccountService currentAccount;

    public MenuItemService(MenuItemRepository repo, CurrentAccountService currentAccount) {
        this.repo = repo;
        this.currentAccount = currentAccount;
    }

    public List<MenuItemDto> list(Boolean available) {
        Long accountId = currentAccount.accountId();
        List<MenuItem> items = available == null
                ? repo.findByAccountIdOrderByCreatedAtDesc(accountId)
                : repo.findByAccountIdAndAvailableOrderByCreatedAtDesc(accountId, available);
        return items.stream().map(MenuItemDto::from).toList();
    }

    @Transactional
    public MenuItemDto create(CreateMenuItemRequest req) {
        MenuItem m = new MenuItem();
        m.setAccountId(currentAccount.accountId());
        m.setName(req.name());
        m.setCategory(req.category());
        m.setPrice(req.price());
        m.setAvailable(req.available() == null || req.available()); // default true
        m.setCreatedAt(Instant.now());
        return MenuItemDto.from(repo.save(m));
    }

    @Transactional
    public MenuItemDto update(Long id, UpdateMenuItemRequest req) {
        MenuItem m = find(id);
        if (req.name() != null) {
            m.setName(req.name());
        }
        if (req.category() != null) {
            m.setCategory(req.category());
        }
        if (req.price() != null) {
            m.setPrice(req.price());
        }
        if (req.available() != null) {
            m.setAvailable(req.available());
        }
        return MenuItemDto.from(repo.save(m));
    }

    @Transactional
    public void delete(Long id) {
        repo.delete(find(id));
    }

    private MenuItem find(Long id) {
        return repo.findByIdAndAccountId(id, currentAccount.accountId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Menu item " + id + " not found"));
    }
}
