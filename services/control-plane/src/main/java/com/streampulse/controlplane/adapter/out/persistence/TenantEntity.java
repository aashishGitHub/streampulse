/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.Tenant;
import com.streampulse.platform.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "tenant")
public class TenantEntity {

    @Id
    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "monthly_event_quota")
    private Long monthlyEventQuota;

    @Column(name = "parent_tenant_id")
    private String parentTenantId;

    @Version
    private Long version;

    /**
     * Lazy, and read-only from this side: the rows are written through their own entities. Touching
     * this on every tenant of a list is the N+1 the lab reproduces.
     */
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Set<AppEntity> apps = new LinkedHashSet<>();

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Set<TitleEntity> titles = new LinkedHashSet<>();

    protected TenantEntity() {}

    static TenantEntity from(Tenant tenant) {
        var e = new TenantEntity();
        e.tenantId = tenant.tenantId().value();
        e.apply(tenant);
        return e;
    }

    void apply(Tenant tenant) {
        this.displayName = tenant.displayName();
        this.monthlyEventQuota = tenant.monthlyEventQuota();
        this.parentTenantId =
                tenant.parentTenantId() == null ? null : tenant.parentTenantId().value();
    }

    Tenant toDomain() {
        return new Tenant(
                TenantId.of(tenantId),
                displayName,
                monthlyEventQuota,
                parentTenantId == null ? null : TenantId.of(parentTenantId),
                version == null ? 0 : version);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getParentTenantId() {
        return parentTenantId;
    }

    public Set<AppEntity> getApps() {
        return apps;
    }

    public Set<TitleEntity> getTitles() {
        return titles;
    }
}
