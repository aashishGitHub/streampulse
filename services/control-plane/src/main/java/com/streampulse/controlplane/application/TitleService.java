/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.application;

import com.streampulse.controlplane.domain.AlreadyExistsException;
import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.TenantRepository;
import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleKind;
import com.streampulse.controlplane.domain.TitleRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TitleService {

    private final TitleRepository titles;
    private final TenantRepository tenants;
    private final ChangeRecorder changes;

    public TitleService(TitleRepository titles, TenantRepository tenants, ChangeRecorder changes) {
        this.titles = titles;
        this.tenants = tenants;
        this.changes = changes;
    }

    @Transactional
    public Title create(Title draft) {
        if (!tenants.exists(draft.tenantId())) {
            throw new NotFoundException("tenant", draft.tenantId().value());
        }
        if (titles.find(draft.titleId()).isPresent()) {
            throw new AlreadyExistsException("title", draft.titleId());
        }
        Title stored = titles.insert(draft);
        changes.created(stored);
        return stored;
    }

    @Transactional(readOnly = true)
    public Title get(String titleId) {
        return titles.find(titleId).orElseThrow(() -> new NotFoundException("title", titleId));
    }

    @Transactional(readOnly = true)
    public List<Title> list(TenantId tenantIdOrNull) {
        return tenantIdOrNull == null ? titles.findAll() : titles.findByTenant(tenantIdOrNull);
    }

    @Transactional
    public Title update(String titleId, String name, TitleKind kind) {
        Title current = get(titleId);
        Title stored = titles.update(current.withDetails(name, kind));
        changes.updated(stored);
        return stored;
    }

    @Transactional
    public void delete(String titleId) {
        Title current = get(titleId);
        titles.delete(titleId);
        changes.deleted(current);
    }
}
