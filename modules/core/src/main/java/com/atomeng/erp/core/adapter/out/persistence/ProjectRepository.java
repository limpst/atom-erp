package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.domain.model.Project;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
}
