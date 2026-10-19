/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web.dto;

import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class TitleDtos {

    private TitleDtos() {}

    public record CreateTitleRequest(
            @NotBlank @Pattern(regexp = AppDtos.ID_PATTERN) String titleId,
            @NotBlank @Pattern(regexp = TenantDtos.TENANT_ID_PATTERN) String tenantId,
            @NotBlank @Size(max = 200) String name,
            @NotNull TitleKind kind) {}

    public record UpdateTitleRequest(@NotBlank @Size(max = 200) String name, @NotNull TitleKind kind) {}

    public record TitleResponse(String titleId, String tenantId, String name, TitleKind kind, long version) {

        public static TitleResponse from(Title t) {
            return new TitleResponse(t.titleId(), t.tenantId().value(), t.name(), t.kind(), t.version());
        }
    }
}
