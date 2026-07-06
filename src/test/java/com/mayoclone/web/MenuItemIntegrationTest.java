package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Menu catalog CRUD + tenant isolation over MockMvc/H2. */
class MenuItemIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-3";

    private Long create(String token, String name, String category, String price, Boolean available)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (category != null) {
            body.put("category", category);
        }
        body.put("price", price);
        if (available != null) {
            body.put("available", available);
        }
        MvcResult res = mvc.perform(post("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.price").value(Double.parseDouble(price)))
                .andReturn();
        Map<?, ?> dto = json.readValue(res.getResponse().getContentAsString(), Map.class);
        return ((Number) dto.get("id")).longValue();
    }

    @Test
    void crudLifecycle() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        Long id = create(token, "Veg Biryani", "Mains", "180", null);

        // list returns the item, available defaulted true
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].category").value("Mains"));

        // patch: price + availability
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("price", "200");
        patch.put("available", false);
        mvc.perform(patch("/api/menu-items/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(200))
                .andExpect(jsonPath("$.available").value(false));

        // ?available=true now excludes it
        mvc.perform(get("/api/menu-items?available=true")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // ?available=false includes it
        mvc.perform(get("/api/menu-items?available=false")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // delete -> 204, then gone
        mvc.perform(delete("/api/menu-items/" + id)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tenantIsolationCrossTenantIs404() throws Exception {
        String tokenA = registerAndToken(uniqueEmail(), PW);
        String tokenB = registerAndToken(uniqueEmail(), PW);

        Long idA = create(tokenA, "A Thali", "Mains", "150", null);

        // B does not see A's items
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // B cannot patch or delete A's item -> 404
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("price", "999");
        mvc.perform(patch("/api/menu-items/" + idA)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(writeJson(patch)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/menu-items/" + idA)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // A still owns it, unchanged
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].price").value(150));
    }
}
