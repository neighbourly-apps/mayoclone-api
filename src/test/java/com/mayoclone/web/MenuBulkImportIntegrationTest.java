package com.mayoclone.web;

import com.mayoclone.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** Bulk-import (.csv) + sample-template (.xlsx) over MockMvc/H2, with tenant isolation. */
class MenuBulkImportIntegrationTest extends AbstractIntegrationTest {

    private static final String PW = "correct-horse-battery-3";
    private static final String XLSX_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "menu.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void csvImportCreatesItemsAndReportsBadRow() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        // Header (case-insensitive), 2 good rows, 1 bad (non-numeric price), 1 blank row.
        String content = String.join("\n",
                "Name,Category,Price,Available",
                "Veg Thali,Thali,120,true",
                "Chicken Biryani,Biryani,180,no",
                "Bad Item,Snacks,notanumber,yes",
                ",,,",
                "Masala Chai,Beverages,20");

        mvc.perform(multipart("/api/menu-items/bulk-import").file(csv(content))
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                // Row 4 in the file (header=1) is the bad-price row.
                .andExpect(jsonPath("$.errors[0].row").value(4))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("price")));

        // The 3 valid items are now visible to this tenant; availability honoured.
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void importedItemsAreTenantScoped() throws Exception {
        String tokenA = registerAndToken(uniqueEmail(), PW);
        String tokenB = registerAndToken(uniqueEmail(), PW);

        mvc.perform(multipart("/api/menu-items/bulk-import")
                        .file(csv("name,category,price,available\nA Thali,Mains,150,true"))
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // B cannot see A's imported item.
        mvc.perform(get("/api/menu-items")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void emptyFileIsRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        mvc.perform(multipart("/api/menu-items/bulk-import")
                        .file(new MockMultipartFile("file", "menu.csv", "text/csv", new byte[0]))
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void headerOnlyCsvIsRejected() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        mvc.perform(multipart("/api/menu-items/bulk-import")
                        .file(csv("name,category,price,available"))
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sampleTemplateReturnsXlsx() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);
        MvcResult res = mvc.perform(get("/api/menu-items/sample-template")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("menu-template.xlsx")))
                .andExpect(header().string("Content-Type", XLSX_TYPE))
                .andReturn();

        byte[] body = res.getResponse().getContentAsByteArray();
        // A real .xlsx is a ZIP — starts with the "PK" local-file-header magic.
        assertThat(body.length).isGreaterThan(0);
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
    }

    @Test
    void xlsxTemplateRoundTripsBackThroughImport() throws Exception {
        String token = registerAndToken(uniqueEmail(), PW);

        byte[] xlsx = mvc.perform(get("/api/menu-items/sample-template")
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        MockMultipartFile file = new MockMultipartFile("file", "menu-template.xlsx",
                XLSX_TYPE, xlsx);

        // The template's 3 example rows import cleanly (proves POI read+write agree).
        mvc.perform(multipart("/api/menu-items/bulk-import").file(file)
                        .header("X-Forwarded-For", uniqueIp())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.skipped").value(0));
    }
}
