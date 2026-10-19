/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streampulse.controlplane.application.AppService;
import com.streampulse.controlplane.application.TenantService;
import com.streampulse.controlplane.application.TenantTreeService;
import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.AppPlatform;
import com.streampulse.controlplane.domain.ConflictException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.RuleViolationException;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantApp;
import com.streampulse.controlplane.domain.TenantTreeNode;
import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleKind;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TenantController.class)
class TenantControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TenantService tenants;

    @MockitoBean
    private TenantTreeService tree;

    @MockitoBean
    private AppService apps;

    private static final String VALID =
            """
            {"tenantId":"acme","displayName":"Acme","monthlyEventQuota":100}""";

    @Test
    @DisplayName("create answers 201 with a Location and the stored representation")
    void createReturns201() throws Exception {
        when(tenants.create(any())).thenReturn(new Tenant(TenantId.of("acme"), "Acme", 100L, null, 0));

        mvc.perform(post("/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/admin/tenants/acme"))
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    @DisplayName("a malformed body is 400 problem+json that names every offending field")
    void validationNamesTheFields() throws Exception {
        mvc.perform(post("/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"Not Valid!\",\"displayName\":\"\",\"monthlyEventQuota\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[?(@ =~ /tenantId.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@ =~ /displayName.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@ =~ /monthlyEventQuota.*/)]").isNotEmpty());
    }

    @Test
    @DisplayName("the reserved id 'tree' is 400, because it would shadow the tree resource")
    void reservedIdIsRefused() throws Exception {
        mvc.perform(post("/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tree\",\"displayName\":\"Tree\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("tenantId 'tree' is reserved"));
    }

    @Test
    @DisplayName("an existing id is 409 problem+json")
    void duplicateIs409() throws Exception {
        when(tenants.create(any())).thenThrow(new AlreadyExistsException("tenant", "acme"));

        mvc.perform(post("/v1/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    @DisplayName("a cycle in the tenant tree is 422 problem+json")
    void cycleIs422() throws Exception {
        when(tenants.update(any(), any(), any(), any()))
                .thenThrow(new RuleViolationException("tenant 'acme' would become its own ancestor via 'kids'"));

        mvc.perform(put("/v1/admin/tenants/acme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Acme\",\"parentTenantId\":\"kids\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("own ancestor")));
    }

    @Test
    @DisplayName("unknown tenant is 404; a still-referenced delete is 409")
    void notFoundAndConflict() throws Exception {
        when(tenants.get(any())).thenThrow(new NotFoundException("tenant", "ghost"));

        mvc.perform(get("/v1/admin/tenants/ghost")).andExpect(status().isNotFound());

        org.mockito.Mockito.doThrow(new ConflictException("still referenced"))
                .when(tenants)
                .delete(any());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/v1/admin/tenants/acme"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the tree is nested: tenant, apps, titles, children")
    void treeShape() throws Exception {
        var acme = new Tenant(TenantId.of("acme"), "Acme", null, null, 0);
        var kids = new Tenant(TenantId.of("kids"), "Kids", null, TenantId.of("acme"), 0);
        when(tree.tree())
                .thenReturn(List.of(new TenantTreeNode(
                        acme,
                        List.of(new TenantApp("web", acme.tenantId(), "Web", AppPlatform.WEB)),
                        List.of(new Title("t1", acme.tenantId(), "Title", TitleKind.VOD, 0)),
                        List.of(new TenantTreeNode(kids, List.of(), List.of(), List.of())))));

        mvc.perform(get("/v1/admin/tenants/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenant.tenantId").value("acme"))
                .andExpect(jsonPath("$[0].apps[0].appId").value("web"))
                .andExpect(jsonPath("$[0].titles[0].titleId").value("t1"))
                .andExpect(jsonPath("$[0].children[0].tenant.tenantId").value("kids"));
    }
}
