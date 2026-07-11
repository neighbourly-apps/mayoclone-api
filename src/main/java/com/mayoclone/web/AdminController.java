package com.mayoclone.web;

import com.mayoclone.dto.AdminAccountDetailDto;
import com.mayoclone.dto.AdminAccountDto;
import com.mayoclone.dto.AdminStatsDto;
import com.mayoclone.security.AccountPrincipal;
import com.mayoclone.security.CurrentAccountService;
import com.mayoclone.service.AdminService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform super-admin panel API. Cross-tenant: these endpoints deliberately see
 * EVERY account, so the whole {@code /api/admin/**} tree is locked to
 * {@code hasRole("SUPER_ADMIN")} in {@code SecurityConfig} and is exempt from the
 * per-tenant 402 subscription gate.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CurrentAccountService currentAccount;

    public AdminController(AdminService adminService, CurrentAccountService currentAccount) {
        this.adminService = adminService;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/stats")
    public AdminStatsDto stats() {
        return adminService.stats();
    }

    @GetMapping("/accounts")
    public Page<AdminAccountDto> accounts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {
        return adminService.listAccounts(q, status, page, size);
    }

    @GetMapping("/accounts/{id}")
    public AdminAccountDetailDto account(@PathVariable Long id) {
        return adminService.getAccount(id);
    }

    @PostMapping("/accounts/{id}/extend-trial")
    public AdminAccountDto extendTrial(@PathVariable Long id, @RequestBody DaysRequest body) {
        return adminService.extendTrial(id, days(body), actorEmail());
    }

    @PostMapping("/accounts/{id}/activate")
    public AdminAccountDto activate(@PathVariable Long id, @RequestBody DaysRequest body) {
        return adminService.activate(id, days(body), actorEmail());
    }

    @PostMapping("/accounts/{id}/suspend")
    public AdminAccountDto suspend(@PathVariable Long id) {
        AccountPrincipal me = currentAccount.require();
        return adminService.suspend(id, me.email(), me.id());
    }

    private String actorEmail() {
        return currentAccount.require().email();
    }

    private static int days(DaysRequest body) {
        return body == null || body.days() == null ? 0 : body.days();
    }

    /** Request body for extend-trial / activate. */
    public record DaysRequest(Integer days) {
    }
}
