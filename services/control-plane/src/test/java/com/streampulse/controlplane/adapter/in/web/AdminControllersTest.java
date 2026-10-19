/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streampulse.controlplane.application.AlertRuleService;
import com.streampulse.controlplane.application.ServiceNodeService;
import com.streampulse.controlplane.application.TitleService;
import com.streampulse.controlplane.domain.RuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({AlertRuleController.class, ServiceNodeController.class, TitleController.class})
class AdminControllersTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AlertRuleService rules;

    @MockitoBean
    private ServiceNodeService nodes;

    @MockitoBean
    private TitleService titles;

    @Test
    @DisplayName("a rule whose dependencies form a cycle is 422 problem+json (the contract's status)")
    void ruleCycleIs422() throws Exception {
        when(rules.create(any())).thenThrow(new RuleViolationException("dependency cycle involving: [CDN, PACKAGER]"));

        mvc.perform(
                        post("/v1/admin/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"ruleId":"r1","tenantId":"acme","metric":"error_rate","threshold":0.1,
                                 "category":"CDN","dependsOn":["PACKAGER"]}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cycle")));
    }

    @Test
    @DisplayName("an unknown enum value or a missing threshold is 400, not 500")
    void badEnumAndMissingField() throws Exception {
        mvc.perform(
                        post("/v1/admin/rules")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"ruleId":"r1","tenantId":"acme","metric":"m","threshold":1,"comparator":"NEAR"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mvc.perform(post("/v1/admin/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleId\":\"r1\",\"tenantId\":\"acme\",\"metric\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@ =~ /threshold.*/)]").isNotEmpty());
    }

    @Test
    @DisplayName("a node and a title with malformed ids are 400 with the field named")
    void malformedIds() throws Exception {
        mvc.perform(post("/v1/admin/service-nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"a/b\",\"tenantId\":\"acme\",\"name\":\"n\",\"category\":\"CDN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@ =~ /nodeId.*/)]").isNotEmpty());

        mvc.perform(post("/v1/admin/titles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"\",\"tenantId\":\"acme\",\"name\":\"n\",\"kind\":\"VOD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@ =~ /titleId.*/)]").isNotEmpty());
    }
}
