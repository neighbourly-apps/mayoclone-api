package com.mayoclone.web;

import com.mayoclone.billing.BillingDtos.BillingStatus;
import com.mayoclone.billing.BillingDtos.CheckoutRequest;
import com.mayoclone.billing.BillingDtos.CheckoutResponse;
import com.mayoclone.billing.BillingDtos.DevActivateRequest;
import com.mayoclone.billing.BillingDtos.InvoiceDto;
import com.mayoclone.billing.BillingDtos.VerifyRequest;
import com.mayoclone.billing.BillingService;
import com.mayoclone.security.CurrentAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trial + subscription endpoints.
 *
 * <ul>
 *   <li>GET  /status       — trial/subscription state + plan + capability flags (auth).</li>
 *   <li>POST /checkout     — create a Razorpay order (503 when not configured) (auth).</li>
 *   <li>POST /verify       — verify a Razorpay payment signature, activate (auth).</li>
 *   <li>POST /webhook      — Razorpay server webhook (PUBLIC; signature-gated).</li>
 *   <li>POST /dev-activate — simulate a payment (dev-mode only) (auth).</li>
 * </ul>
 *
 * <p>All routes except {@code /webhook} require a Bearer JWT. {@code /webhook} is
 * permitted in {@code SecurityConfig} and authenticated by its Razorpay signature.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final BillingService billing;
    private final CurrentAccountService currentAccount;

    public BillingController(BillingService billing, CurrentAccountService currentAccount) {
        this.billing = billing;
        this.currentAccount = currentAccount;
    }

    @GetMapping("/status")
    public BillingStatus status() {
        return billing.status(currentAccount.accountId());
    }

    @GetMapping("/invoices")
    public java.util.List<InvoiceDto> invoices() {
        return billing.invoices(currentAccount.accountId());
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(@RequestBody(required = false) CheckoutRequest req) {
        return billing.checkout(currentAccount.accountId(), req == null ? null : req.plan());
    }

    @PostMapping("/verify")
    public BillingStatus verify(@RequestBody VerifyRequest req) {
        return billing.verify(currentAccount.accountId(),
                req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature(), req.plan());
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        billing.webhook(rawBody == null ? "" : rawBody, signature);
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }

    @PostMapping("/dev-activate")
    public BillingStatus devActivate(@RequestBody(required = false) DevActivateRequest req) {
        return billing.devActivate(currentAccount.accountId(), req == null ? null : req.plan());
    }
}
