package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bulk status + assign: partial success (updated + failed) semantics. */
class OrderBulkOperationsIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-bulk";

    private Long createDirect(String token) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Veg Thali");
        item.put("qty", 1);
        item.put("price", "150");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trainNumber", "12951");
        body.put("deliveryStationCode", "NDLS");
        body.put("passengerName", "P");
        body.put("passengerPhone", "9000000000");
        body.put("deliveryDate", LocalDate.now().toString());
        body.put("paymentMode", "PREPAID");
        body.put("amount", "150");
        body.put("items", List.of(item));
        MvcResult res = mvc.perform(post("/api/orders/direct")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) json.readValue(res.getResponse().getContentAsString(), Map.class)
                .get("id")).longValue();
    }

    private Long createRider(String token, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("phone", "9000000000");
        MvcResult res = mvc.perform(post("/api/riders")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) json.readValue(res.getResponse().getContentAsString(), Map.class)
                .get("id")).longValue();
    }

    @Test
    void bulkStatusMixedValidAndInvalid() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long a = createDirect(token); // NEW -> ACCEPTED ok
        Long b = createDirect(token); // NEW -> ACCEPTED ok
        Long missing = 999999L;       // unknown -> failed (404)

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", List.of(a, b, missing));
        body.put("status", "ACCEPTED");
        body.put("note", "batch accept");

        mvc.perform(post("/api/orders/bulk/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated.length()").value(2))
                .andExpect(jsonPath("$.updated", org.hamcrest.Matchers.containsInAnyOrder(
                        a.intValue(), b.intValue())))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value(missing.intValue()));

        // an illegal transition also lands in failed
        Map<String, Object> illegal = new LinkedHashMap<>();
        illegal.put("ids", List.of(a));      // a is ACCEPTED now; NEW is illegal
        illegal.put("status", "NEW");
        mvc.perform(post("/api/orders/bulk/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(illegal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value(a.intValue()));
    }

    @Test
    void bulkAssignSkipsUnknownAndCrossTenant() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long a = createDirect(token);
        Long b = createDirect(token);
        Long rider = createRider(token, "R1");

        // cross-tenant order id from another account -> failed
        String tokenB = registerAndToken(uniqueEmail(), PW);
        Long foreign = createDirect(tokenB);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", List.of(a, b, foreign));
        body.put("riderId", rider);
        mvc.perform(post("/api/orders/bulk/assign")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated.length()").value(2))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].id").value(foreign.intValue()));

        // verify a is assigned
        mvc.perform(get("/api/orders/" + a)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.riderId").value(rider.intValue()));

        // unknown rider -> all failed
        Map<String, Object> badRider = new LinkedHashMap<>();
        badRider.put("ids", List.of(a));
        badRider.put("riderId", 888888);
        mvc.perform(post("/api/orders/bulk/assign")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(badRider)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated.length()").value(0))
                .andExpect(jsonPath("$.failed.length()").value(1));
    }
}
