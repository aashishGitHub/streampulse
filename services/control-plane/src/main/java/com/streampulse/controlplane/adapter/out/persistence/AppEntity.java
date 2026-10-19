/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.AppPlatform;
import com.streampulse.controlplane.domain.TenantApp;
import com.streampulse.platform.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app")
public class AppEntity {

    @Id
    @Column(name = "app_id")
    private String appId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AppPlatform platform;

    protected AppEntity() {}

    static AppEntity from(TenantApp app) {
        var e = new AppEntity();
        e.appId = app.appId();
        e.tenantId = app.tenantId().value();
        e.name = app.name();
        e.platform = app.platform();
        return e;
    }

    TenantApp toDomain() {
        return new TenantApp(appId, TenantId.of(tenantId), name, platform);
    }
}
