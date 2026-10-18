package com.atomeng.erp.fac.adapter.out.persistence;

import com.atomeng.erp.fac.domain.model.Site;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteRepository extends JpaRepository<Site, UUID> {
}
