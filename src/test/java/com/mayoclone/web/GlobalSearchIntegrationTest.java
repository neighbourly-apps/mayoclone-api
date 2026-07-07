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

/** Global order search: field matching, tenant scoping, limit cap. */
class GlobalSearchIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-search";

    private Long createDirect(String token, String pnr, String phone, String passenger,
                              String train, String station) throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Veg Thali");
        item.put("qty", 1);
        item.put("price", "150");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trainNumber", train);
        body.put("trainName", "Rajdhani");
        body.put("pnr", pnr);
        body.put("deliveryStationCode", station);
        body.put("passengerName", passenger);
        body.put("passengerPhone", phone);
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
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return ((Number) dto.get("id")).longValue();
    }

    @Test
    void matchesPnrPhoneAndTrainAndIsTenantScoped() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        Long o1 = createDirect(token, "PNR1234567", "9876543210", "Alice Kumar", "12951", "NDLS");
        createDirect(token, "PNR9999999", "9000000000", "Bob Singh", "22222", "ALD");

        // match by pnr
        mvc.perform(get("/api/search?q=PNR1234567")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("PNR1234567"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.orders[0].id").value(o1.intValue()));

        // match by phone (case-insensitive substring on name too)
        mvc.perform(get("/api/search?q=9876543210")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.orders[0].passengerPhone").value("9876543210"));

        // match by train number
        mvc.perform(get("/api/search?q=12951")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));

        // case-insensitive name match
        mvc.perform(get("/api/search?q=alice")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));

        // tenant B sees nothing
        String tokenB = registerAndToken(uniqueEmail(), PW);
        mvc.perform(get("/api/search?q=PNR1234567")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.orders.length()").value(0));
    }

    @Test
    void blankQueryReturnsEmpty() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        createDirect(token, "PNR5555555", "9111111111", "Cara", "13001", "HWH");
        mvc.perform(get("/api/search?q=")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
