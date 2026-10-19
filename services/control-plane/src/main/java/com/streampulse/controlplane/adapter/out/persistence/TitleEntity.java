/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleKind;
import com.streampulse.platform.domain.TenantId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "title")
public class TitleEntity {

    @Id
    @Column(name = "title_id")
    private String titleId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TitleKind kind;

    @Version
    private Long version;

    protected TitleEntity() {}

    static TitleEntity from(Title title) {
        var e = new TitleEntity();
        e.titleId = title.titleId();
        e.tenantId = title.tenantId().value();
        e.apply(title);
        return e;
    }

    void apply(Title title) {
        this.name = title.name();
        this.kind = title.kind();
    }

    Title toDomain() {
        return new Title(titleId, TenantId.of(tenantId), name, kind, version == null ? 0 : version);
    }
}
