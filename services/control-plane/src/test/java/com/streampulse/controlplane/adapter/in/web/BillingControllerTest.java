/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BillingController.class)
class BillingControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("usage is deferred: 501 problem+json that says so, never an invented figure")
    void usageIsNotImplemented() throws Exception {
        mvc.perform(get("/v1/billing/usage").param("month", "2026-10"))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("deferred")))
                .andExpect(jsonPath("$.usage").doesNotExist());
    }

    @Test
    @DisplayName("a malformed month is still 400")
    void malformedMonth() throws Exception {
        mvc.perform(get("/v1/billing/usage").param("month", "October")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/billing/usage")).andExpect(status().isBadRequest());
    }
}
