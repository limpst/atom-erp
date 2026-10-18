package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.domain.model.Client;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, UUID> {
    long countByName(String name);
}
