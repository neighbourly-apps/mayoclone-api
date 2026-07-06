package com.mayoclone.ingest;

import com.mayoclone.domain.Account;
import com.mayoclone.domain.IngestFailure;
import com.mayoclone.domain.IngestFailureReason;
import com.mayoclone.domain.MailSourceType;
import com.mayoclone.dto.IngestResult;
import com.mayoclone.repository.AccountRepository;
import com.mayoclone.repository.IngestFailureRepository;
import com.mayoclone.repository.IrctcOrderRepository;
import com.mayoclone.security.AccountPrincipal;
import com.mayoclone.service.IngestFailureService;
import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real route→parse→dedup→save pipeline and the tenant-scoped review
 * queue against H2 (no Docker), using the actual wired beans (aggregators are
 * seeded at startup). Complements the pure-Mockito {@code IngestionCoreTest} (which
 * covers PARSE_FAILED via a throwing mock parser — the production
 * GenericIrctcEmailParser never throws).
 */
class IngestionReviewQueueIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IngestionCore ingestionCore;

    @Autowired
    private IngestFailureService ingestFailureService;

    @Autowired
    private IngestFailureRepository failureRepo;

    @Autowired
    private IrctcOrderRepository orderRepo;

    @Autowired
    private AccountRepository accountRepo;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unknownSenderIsDeadLetteredAsNoAggregatorMatch() throws Exception {
        Long accountId = newAccount().getId();
        RawMessage msg = new RawMessage("orders@unknown-domain.example", "hi", "body", "<nam-1@x>");

        IngestResult result = ingestionCore.process(accountId, null, MailSourceType.FORWARDING, msg);

        assertEquals(0, result.newOrders());
        List<IngestFailure> failures = failureRepo.findByAccountIdOrderByCreatedAtDesc(accountId);
        assertEquals(1, failures.size());
        assertEquals(IngestFailureReason.NO_AGGREGATOR_MATCH, failures.get(0).getReason());
    }

    @Test
    void sameMessageIdIngestedTwiceStoresExactlyOneOrder() throws Exception {
        Long accountId = newAccount().getId();
        RawMessage msg = new RawMessage("orders@zoopindia.com",
                "Zoop order confirmed", zoopBody(), "<dedup-same@zoopindia.com>");

        IngestResult first = ingestionCore.process(accountId, null, MailSourceType.FORWARDING, msg);
        IngestResult second = ingestionCore.process(accountId, null, MailSourceType.FORWARDING, msg);

        assertEquals(1, first.newOrders());
        assertEquals(0, second.newOrders(), "second ingest of the same messageId is deduped");
        assertEquals(1, orderRepo.findByAccountIdOrderByPlacedAtDesc(accountId).size());
    }

    @Test
    void retryThatNowSucceedsRemovesFailureAndCreatesOrder() throws Exception {
        Account account = newAccount();
        Long accountId = account.getId();

        // A previously dead-lettered message that will now parse cleanly.
        IngestFailure failure = new IngestFailure();
        failure.setAccountId(accountId);
        failure.setFromAddress("orders@zoopindia.com");
        failure.setSubject("Zoop order confirmed");
        failure.setReason(IngestFailureReason.NO_AGGREGATOR_MATCH);
        failure.setRawSnippet(zoopBody());
        failure.setSourceType(MailSourceType.FORWARDING);
        failure.setMessageId("<retry-1@zoopindia.com>");
        failure.setCreatedAt(Instant.now());
        Long failureId = failureRepo.save(failure).getId();

        authenticateAs(account);
        IngestResult result = ingestFailureService.retry(failureId);

        assertEquals(1, result.newOrders());
        assertTrue(failureRepo.findByIdAndAccountId(failureId, accountId).isEmpty(),
                "the failure row is removed after a successful retry");
        assertEquals(1, orderRepo.findByAccountIdOrderByPlacedAtDesc(accountId).size());
    }

    private Account newAccount() {
        Account a = new Account();
        a.setBusinessName("Queue Biz");
        a.setEmail(uniqueEmail());
        a.setPasswordHash("noop");
        a.setRole(Account.ROLE_OWNER);
        a.setStatus(Account.STATUS_ACTIVE);
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    private static void authenticateAs(Account account) {
        AccountPrincipal principal = new AccountPrincipal(account.getId(), account.getEmail(), account.getRole());
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static String zoopBody() {
        return """
                Dear Rajesh Kumar,
                Your Zoop e-catering order is confirmed.
                Order ID: ZOOPQ1
                PNR: 4512367890
                Train No.: 12951 Mumbai Rajdhani Express
                Delivery Station: NDLS New Delhi
                Passenger: Rajesh Kumar
                Delivery Date: 2026-07-08
                2 x Veg Biryani - ₹180
                Total: ₹360
                """;
    }
}
