package com.mayoclone.enrich.irctc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mayoclone.enrich.OrderEnrichment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (no-network) unit test for the IRCTC order-detail JSON → {@link OrderEnrichment}
 * mapping and the auth-failure body detection. Exercises the package-visible static
 * {@code parseOrder}/{@code isAuthFailure} helpers so the live site is never touched.
 */
class IrctcEcateringScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Representative {@code GET /order/{id}} response for the IRCTC eCatering API. */
    private static final String ORDER_JSON = """
            {
              "status": "success",
              "result": {
                "orderId": "ECAT12345",
                "pnr": "2857463910",
                "trainName": "GATIMAAN EXP",
                "coach": "B5",
                "berth": 64,
                "customer": {
                  "fullName": "Aayush Jain",
                  "mobile": "9876543210"
                }
              }
            }
            """;

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void parseOrder_mapsCustomerNamePhoneCoachAndBerth() throws Exception {
        OrderEnrichment e = IrctcEcateringScraper.parseOrder(tree(ORDER_JSON));

        assertEquals("Aayush Jain", e.passengerName());
        assertEquals("9876543210", e.passengerPhone());
        assertEquals("B5", e.coach());
        assertEquals("64", e.berth(), "numeric berth must be stringified");
        assertEquals("2857463910", e.pnr());
        assertNull(e.deliverySlot());
    }

    @Test
    void parseOrder_nullWhenResultMissing() throws Exception {
        assertNull(IrctcEcateringScraper.parseOrder(tree("{\"status\":\"success\"}")));
        assertNull(IrctcEcateringScraper.parseOrder(null));
    }

    @Test
    void parseOrder_nullWhenNameMissing() throws Exception {
        String json = "{\"result\":{\"coach\":\"B5\",\"customer\":{\"mobile\":\"9876543210\"}}}";
        assertNull(IrctcEcateringScraper.parseOrder(tree(json)));
    }

    @Test
    void isAuthFailure_trueForSessionRejectionBody() throws Exception {
        String json = "{\"status\":\"failure\",\"message\":\"Please login to continue.\"}";
        assertTrue(IrctcEcateringScraper.isAuthFailure(tree(json)));
    }

    @Test
    void isAuthFailure_falseForOrdinaryFailureOrSuccess() throws Exception {
        assertFalse(IrctcEcateringScraper.isAuthFailure(
                tree("{\"status\":\"failure\",\"message\":\"order not found\"}")));
        assertFalse(IrctcEcateringScraper.isAuthFailure(tree(ORDER_JSON)));
        assertFalse(IrctcEcateringScraper.isAuthFailure(null));
    }
}
