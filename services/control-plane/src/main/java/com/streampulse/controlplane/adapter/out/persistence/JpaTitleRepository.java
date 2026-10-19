/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import com.streampulse.controlplane.domain.NotFoundException;
import com.streampulse.controlplane.domain.Title;
import com.streampulse.controlplane.domain.TitleRepository;
import com.streampulse.platform.domain.TenantId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaTitleRepository implements TitleRepository {

    private final TitleJpaRepository repository;

    public JpaTitleRepository(TitleJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Title> find(String titleId) {
        return repository.findById(titleId).map(TitleEntity::toDomain);
    }

    @Override
    public List<Title> findAll() {
        return repository.findAll().stream().map(TitleEntity::toDomain).toList();
    }

    @Override
    public List<Title> findByTenant(TenantId tenantId) {
        return repository.findByTenantIdOrderByTitleId(tenantId.value()).stream()
                .map(TitleEntity::toDomain)
                .toList();
    }

    @Override
    public Title insert(Title title) {
        return repository.saveAndFlush(TitleEntity.from(title)).toDomain();
    }

    @Override
    public Title update(Title title) {
        TitleEntity entity =
                repository.findById(title.titleId()).orElseThrow(() -> new NotFoundException("title", title.titleId()));
        entity.apply(title);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    public void delete(String titleId) {
        repository.deleteById(titleId);
        repository.flush();
    }
}
