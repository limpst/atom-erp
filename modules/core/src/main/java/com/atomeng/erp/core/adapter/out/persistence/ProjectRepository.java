package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.domain.model.Project;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** 목록(웹 프로젝트 화면) — 최신 생성 순 */
    java.util.List<Project> findAllByOrderByCreatedAtDesc();

    java.util.List<Project> findAllByIdInOrderByCreatedAtDesc(java.util.Collection<UUID> ids);

    java.util.List<Project> findAllByClientIdOrderByCreatedAtDesc(UUID clientId);
}
