package com.mayoclone.web;

import com.mayoclone.ingest.IngestionCore;
import com.mayoclone.ingest.inbound.MailgunSignatureVerifier;
import com.mayoclone.observability.AppMetrics;
import com.mayoclone.repository.VendorRepository;
import com.mayoclone.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit test for the disabled-feature path: with no Mailgun signing key configured,
 * the endpoint returns 503 without touching any downstream collaborator.
 */
class InboundMailgunControllerTest {

    @Test
    void returns503WhenSigningKeyIsUnset() {
        VendorRepository vendorRepo = mock(VendorRepository.class);
        IngestionCore ingestionCore = mock(IngestionCore.class);
        AppMetrics metrics = mock(AppMetrics.class);
        AuditService audit = mock(AuditService.class);

        InboundMailgunController controller = new InboundMailgunController(
                MailgunSignatureVerifier.withKey(""), // disabled
                vendorRepo, ingestionCore, metrics, audit);

        ResponseEntity<Map<String, Object>> res = controller.receive(Map.of(
                "recipient", "x@inbound.mayoclone.test",
                "timestamp", "1", "token", "t", "signature", "aa"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        verifyNoInteractions(vendorRepo, ingestionCore);
    }
}
