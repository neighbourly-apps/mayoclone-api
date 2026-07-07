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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage over MockMvc/H2 for the order-operations feature set:
 * direct-order create, status transitions (valid + illegal 409), rider assignment
 * (+ cross-tenant 404), status-event timeline, list filters, and the daily
 * dashboard math.
 */
class OrderOperationsIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-2";

    // ---------------------------------------------------------------- helpers

    private Long createDirect(String token, String paymentMode, String amount, String date) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Veg Thali");
        item.put("qty", 2);
        item.put("price", "150");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trainNumber", "12951");
        body.put("trainName", "Rajdhani");
        body.put("deliveryStationCode", "NDLS");
        body.put("passengerName", "Test Passenger");
        body.put("passengerPhone", "9876543210");
        body.put("deliveryDate", date);
        body.put("paymentMode", paymentMode);
        body.put("items", List.of(item));
        if (amount != null) {
            body.put("amount", amount);
        }

        MvcResult res = mvc.perform(post("/api/orders/direct")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderType").value("DIRECT"))
                .andExpect(jsonPath("$.aggregator").doesNotExist())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.externalOrderId").value(org.hamcrest.Matchers.startsWith("DIR-")))
                .andExpect(jsonPath("$.statusHistory.length()").value(1))
                .andExpect(jsonPath("$.statusHistory[0].status").value("NEW"))
                .andReturn();
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return ((Number) dto.get("id")).longValue();
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
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return ((Number) dto.get("id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(
            String token, Long orderId, String newStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", newStatus);
        body.put("note", "test");
        return mvc.perform(patch("/api/orders/" + orderId + "/status")
                .header("X-Forwarded-For", uniqueIp())
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(writeJson(body)));
    }

    private org.springframework.test.web.servlet.ResultActions patchAssign(
            String token, Long orderId, Long riderId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("riderId", riderId);
        return mvc.perform(patch("/api/orders/" + orderId + "/assign")
                .header("X-Forwarded-For", uniqueIp())
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(writeJson(body)));
    }

    // ---------------------------------------------------------------- tests

    @Test
    void directOrderCreateSetsDirectTypeAndNewStatus() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        // amount omitted -> should equal sum(items) = 2 * 150 = 300
        Long id = createDirect(token, "COD", null, LocalDate.now().toString());
        mvc.perform(get("/api/orders/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentMode").value("COD"))
                .andExpect(jsonPath("$.amount").value(300));
    }

    @Test
    void validStatusTransitionsAppendTimelineAndSetDeliveredAt() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long id = createDirect(token, "PREPAID", "300", LocalDate.now().toString());

        patchStatus(token, id, "ACCEPTED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.statusHistory.length()").value(2));
        patchStatus(token, id, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        patchStatus(token, id, "BILL_PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BILL_PENDING"))
                .andExpect(jsonPath("$.deliveredAt").exists())
                .andExpect(jsonPath("$.statusHistory.length()").value(4));
    }

    @Test
    void illegalStatusTransitionIsRejectedWith409() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long id = createDirect(token, "PREPAID", "300", LocalDate.now().toString());
        // NEW -> OUT_FOR_DELIVERY is not allowed (must go via ACCEPTED).
        patchStatus(token, id, "OUT_FOR_DELIVERY").andExpect(status().isConflict());
    }

    @Test
    void transitionGraphEnforcesTheFiveStateModel() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        // NEW -> BILL_PENDING (skipping) is illegal.
        Long a = createDirect(token, "PREPAID", "300", LocalDate.now().toString());
        patchStatus(token, a, "BILL_PENDING").andExpect(status().isConflict());

        // Any non-terminal may be cancelled: ACCEPTED -> CANCELLED is legal, then terminal.
        Long b = createDirect(token, "PREPAID", "300", LocalDate.now().toString());
        patchStatus(token, b, "ACCEPTED").andExpect(status().isOk());
        patchStatus(token, b, "CANCELLED").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // CANCELLED is terminal — no further transitions.
        patchStatus(token, b, "ACCEPTED").andExpect(status().isConflict());

        // Full happy path to BILL_PENDING, which is terminal (cannot be cancelled after).
        Long c = createDirect(token, "PREPAID", "300", LocalDate.now().toString());
        patchStatus(token, c, "ACCEPTED").andExpect(status().isOk());
        patchStatus(token, c, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        patchStatus(token, c, "BILL_PENDING").andExpect(status().isOk());
        patchStatus(token, c, "CANCELLED").andExpect(status().isConflict());
    }

    @Test
    void assignRiderSetsRiderAndCrossTenantIs404() throws Exception {
        String tokenA = registerAndToken(uniqueEmail(), PW);
        Long orderA = createDirect(tokenA, "PREPAID", "300", LocalDate.now().toString());
        Long riderA = createRider(tokenA, "Rider A");

        patchAssign(tokenA, orderA, riderA).andExpect(status().isOk())
                .andExpect(jsonPath("$.riderId").value(riderA.intValue()))
                .andExpect(jsonPath("$.riderName").value("Rider A"))
                .andExpect(jsonPath("$.assignedAt").exists());

        // Account B cannot assign or change status on A's order -> 404 (not 403).
        String tokenB = registerAndToken(uniqueEmail(), PW);
        patchAssign(tokenB, orderA, null).andExpect(status().isNotFound());
        patchStatus(tokenB, orderA, "ACCEPTED").andExpect(status().isNotFound());

        // Clearing the assignment nulls rider + assignedAt.
        patchAssign(tokenA, orderA, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.riderId").doesNotExist())
                .andExpect(jsonPath("$.assignedAt").doesNotExist());
    }

    @Test
    void listFiltersByStatusTypePaymentAndRider() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        String today = LocalDate.now().toString();
        Long o1 = createDirect(token, "PREPAID", "300", today);
        createDirect(token, "COD", "300", today);
        Long rider = createRider(token, "R1");
        patchAssign(token, o1, rider).andExpect(status().isOk());

        // orderType=DIRECT -> both
        mvc.perform(get("/api/orders?orderType=DIRECT")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));
        // paymentMode=COD -> one
        mvc.perform(get("/api/orders?paymentMode=COD")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1));
        // status=NEW -> both
        mvc.perform(get("/api/orders?status=NEW")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(2));
        // riderId -> only the assigned one
        mvc.perform(get("/api/orders?riderId=" + rider)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(o1.intValue()));
    }

    @Test
    void dailyDashboardSplitsOnlineVsCodAndCountsByStatus() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        String today = LocalDate.now().toString();
        Long a = createDirect(token, "PREPAID", "300", today);
        createDirect(token, "PREPAID", "200", today);
        createDirect(token, "COD", "100", today);
        // Advance one order to ACCEPTED so byStatus reflects it.
        patchStatus(token, a, "ACCEPTED").andExpect(status().isOk());

        mvc.perform(get("/api/dashboard/daily?date=" + today)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.online.count").value(2))
                .andExpect(jsonPath("$.online.revenue").value(500))
                .andExpect(jsonPath("$.cod.count").value(1))
                .andExpect(jsonPath("$.cod.revenue").value(100))
                .andExpect(jsonPath("$.byStatus.NEW").value(2))
                .andExpect(jsonPath("$.byStatus.ACCEPTED").value(1))
                .andExpect(jsonPath("$.byStatus.BILL_PENDING").value(0))
                .andExpect(jsonPath("$.unassigned").value(3))
                .andExpect(jsonPath("$.undelivered").value(0));
    }
}
