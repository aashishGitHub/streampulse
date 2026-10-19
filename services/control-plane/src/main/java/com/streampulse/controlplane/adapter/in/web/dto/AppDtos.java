/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.in.web.dto;

import com.streampulse.controlplane.domain.AppPlatform;
import com.streampulse.controlplane.domain.TenantApp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AppDtos {

    public static final String ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$";

    private AppDtos() {}

    public record CreateAppRequest(
            @NotBlank @Pattern(regexp = ID_PATTERN) String appId,
            @NotBlank @Size(max = 120) String name,
            @NotNull AppPlatform platform) {}

    public record AppResponse(String appId, String tenantId, String name, AppPlatform platform) {

        public static AppResponse from(TenantApp a) {
            return new AppResponse(a.appId(), a.tenantId().value(), a.name(), a.platform());
        }
    }
}
