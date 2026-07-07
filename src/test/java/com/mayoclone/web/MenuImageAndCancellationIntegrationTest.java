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

/** Menu item imageUrl round-trip + order cancellationReason capture. */
class MenuImageAndCancellationIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-imgcancel";

    @Test
    void menuItemImageUrlRoundTrip() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Paneer Tikka");
        body.put("category", "Starters");
        body.put("imageUrl", "https://cdn.test/paneer.jpg");
        body.put("price", "220");

        MvcResult res = mvc.perform(post("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.test/paneer.jpg"))
                .andReturn();
        Long id = ((Number) json.readValue(res.getResponse().getContentAsString(), Map.class)
                .get("id")).longValue();

        // patch imageUrl
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("imageUrl", "https://cdn.test/paneer-v2.jpg");
        mvc.perform(patch("/api/menu-items/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.test/paneer-v2.jpg"));

        // list carries imageUrl
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].imageUrl").value("https://cdn.test/paneer-v2.jpg"));
    }

    @Test
    void cancellationReasonStoredOnCancel() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Thali");
        item.put("qty", 1);
        item.put("price", "150");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("trainNumber", "12951");
        order.put("deliveryStationCode", "NDLS");
        order.put("passengerName", "P");
        order.put("passengerPhone", "9000000000");
        order.put("deliveryDate", LocalDate.now().toString());
        order.put("paymentMode", "PREPAID");
        order.put("amount", "150");
        order.put("items", List.of(item));
        MvcResult res = mvc.perform(post("/api/orders/direct")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(order)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancellationReason").doesNotExist())
                .andReturn();
        Long id = ((Number) json.readValue(res.getResponse().getContentAsString(), Map.class)
                .get("id")).longValue();

        // NEW -> CANCELLED with a reason
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("status", "CANCELLED");
        cancel.put("cancellationReason", "passenger deboarded early");
        mvc.perform(patch("/api/orders/" + id + "/status")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(cancel)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("passenger deboarded early"));

        // persisted
        mvc.perform(get("/api/orders/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.cancellationReason").value("passenger deboarded early"));
    }
}
