/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web.dto;

import com.streampulse.controlplane.domain.Category;
import com.streampulse.controlplane.domain.ServiceNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

public final class ServiceNodeDtos {

    private ServiceNodeDtos() {}

    public record CreateServiceNodeRequest(
            @NotBlank @Pattern(regexp = AppDtos.ID_PATTERN) String nodeId,
            @NotBlank @Pattern(regexp = TenantDtos.TENANT_ID_PATTERN) String tenantId,
            @NotBlank @Size(max = 120) String name,
            @NotNull Category category,
            Set<@Pattern(regexp = AppDtos.ID_PATTERN) String> dependsOn) {}

    public record UpdateServiceNodeRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull Category category,
            Set<@Pattern(regexp = AppDtos.ID_PATTERN) String> dependsOn) {}

    public record ServiceNodeResponse(
            String nodeId, String tenantId, String name, Category category, List<String> dependsOn, long version) {

        public static ServiceNodeResponse from(ServiceNode n) {
            return new ServiceNodeResponse(
                    n.nodeId(),
                    n.tenantId().value(),
                    n.name(),
                    n.category(),
                    n.dependsOn().stream().sorted().toList(),
                    n.version());
        }
    }
}
