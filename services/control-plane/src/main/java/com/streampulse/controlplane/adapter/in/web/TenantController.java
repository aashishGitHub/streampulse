/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web;

import com.streampulse.controlplane.adapter.in.web.dto.AppDtos.AppResponse;
import com.streampulse.controlplane.adapter.in.web.dto.AppDtos.CreateAppRequest;
import com.streampulse.controlplane.adapter.in.web.dto.TenantDtos.CreateTenantRequest;
import com.streampulse.controlplane.adapter.in.web.dto.TenantDtos.TenantResponse;
import com.streampulse.controlplane.adapter.in.web.dto.TenantDtos.TenantTreeResponse;
import com.streampulse.controlplane.adapter.in.web.dto.TenantDtos.UpdateTenantRequest;
import com.streampulse.controlplane.application.AppService;
import com.streampulse.controlplane.application.TenantService;
import com.streampulse.controlplane.application.TenantTreeService;
import com.streampulse.controlplane.domain.Ids;
import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.platform.domain.TenantId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantController {

    private final TenantService tenants;
    private final TenantTreeService tree;
    private final AppService apps;

    public TenantController(TenantService tenants, TenantTreeService tree, AppService apps) {
        this.tenants = tenants;
        this.tree = tree;
        this.apps = apps;
    }

    @GetMapping
    public List<TenantResponse> list() {
        return tenants.list().stream().map(TenantResponse::from).toList();
    }

    /** A literal segment wins over {@code /{tenantId}}, which is why the id {@code tree} is reserved. */
    @GetMapping("/tree")
    public List<TenantTreeResponse> tree() {
        return tree.tree().stream().map(TenantTreeResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant stored = tenants.create(Tenant.draft(
                request.tenantId(), request.displayName(), request.monthlyEventQuota(), request.parentTenantId()));
        return ResponseEntity.created(URI.create("/v1/admin/tenants/" + stored.tenantId()))
                .body(TenantResponse.from(stored));
    }

    @GetMapping("/{tenantId}")
    public TenantResponse get(@PathVariable String tenantId) {
        return TenantResponse.from(tenants.get(Ids.tenantId(tenantId)));
    }

    @PutMapping("/{tenantId}")
    public TenantResponse update(@PathVariable String tenantId, @Valid @RequestBody UpdateTenantRequest request) {
        TenantId parent = request.parentTenantId() == null ? null : Ids.tenantId(request.parentTenantId());
        return TenantResponse.from(
                tenants.update(Ids.tenantId(tenantId), request.displayName(), request.monthlyEventQuota(), parent));
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<Void> delete(@PathVariable String tenantId) {
        tenants.delete(Ids.tenantId(tenantId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{tenantId}/apps")
    public List<AppResponse> listApps(@PathVariable String tenantId) {
        return apps.list(Ids.tenantId(tenantId)).stream().map(AppResponse::from).toList();
    }

    @PostMapping("/{tenantId}/apps")
    public ResponseEntity<AppResponse> createApp(
            @PathVariable String tenantId, @Valid @RequestBody CreateAppRequest request) {
        var stored = apps.create(Ids.tenantId(tenantId), request.appId(), request.name(), request.platform());
        return ResponseEntity.created(URI.create("/v1/admin/tenants/" + tenantId + "/apps/" + stored.appId()))
                .body(AppResponse.from(stored));
    }

    @DeleteMapping("/{tenantId}/apps/{appId}")
    public ResponseEntity<Void> deleteApp(@PathVariable String tenantId, @PathVariable String appId) {
        apps.delete(Ids.tenantId(tenantId), appId);
        return ResponseEntity.noContent().build();
    }
}
