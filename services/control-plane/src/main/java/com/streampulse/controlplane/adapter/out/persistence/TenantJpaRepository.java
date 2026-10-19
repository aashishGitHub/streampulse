/* StreamPulse — see docs/ and specs/ for the design. */

package com.streampulse.controlplane.adapter.out.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantJpaRepository extends JpaRepository<TenantEntity, String> {

    @Query("select t from TenantEntity t left join fetch t.apps")
    List<TenantEntity> findAllWithApps();

    /**
     * Run AFTER {@link #findAllWithApps()} in the same persistence context: Hibernate fills the
     * titles of the instances it already holds. Fetching both bags in one statement would either
     * throw (MultipleBagFetchException) or return apps x titles rows per tenant.
     */
    @Query("select t from TenantEntity t left join fetch t.titles")
    List<TenantEntity> findAllWithTitles();
}
