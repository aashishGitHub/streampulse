/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web.dto;

import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.controlplane.domain.TenantTreeNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class TenantDtos {

    /** Same pattern as com.streampulse.platform.domain.TenantId and the OpenAPI schema. */
    public static final String TENANT_ID_PATTERN = "^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$";

    private TenantDtos() {}

    public record CreateTenantRequest(
            @NotBlank @Pattern(regexp = TENANT_ID_PATTERN) String tenantId,
            @NotBlank @Size(max = 120) String displayName,
            @PositiveOrZero Long monthlyEventQuota,
            @Pattern(regexp = TENANT_ID_PATTERN) String parentTenantId) {}

    public record UpdateTenantRequest(
            @NotBlank @Size(max = 120) String displayName,
            @PositiveOrZero Long monthlyEventQuota,
            @Pattern(regexp = TENANT_ID_PATTERN) String parentTenantId) {}

    public record TenantResponse(
            String tenantId, String displayName, Long monthlyEventQuota, String parentTenantId, long version) {

        public static TenantResponse from(Tenant t) {
            return new TenantResponse(
                    t.tenantId().value(),
                    t.displayName(),
                    t.monthlyEventQuota(),
                    t.parentTenantId() == null ? null : t.parentTenantId().value(),
                    t.version());
        }
    }

    public record TenantTreeResponse(
            TenantResponse tenant,
            List<AppDtos.AppResponse> apps,
            List<TitleDtos.TitleResponse> titles,
            List<TenantTreeResponse> children) {

        public static TenantTreeResponse from(TenantTreeNode node) {
            return new TenantTreeResponse(
                    TenantResponse.from(node.tenant()),
                    node.apps().stream().map(AppDtos.AppResponse::from).toList(),
                    node.titles().stream().map(TitleDtos.TitleResponse::from).toList(),
                    node.children().stream().map(TenantTreeResponse::from).toList());
        }
    }
}
