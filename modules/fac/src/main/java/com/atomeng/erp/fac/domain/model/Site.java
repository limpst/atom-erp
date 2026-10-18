package com.atomeng.erp.fac.domain.model;

import com.atomeng.common.core.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

/** 단지·캠퍼스 (BR-FAC-001: 단독 건물은 같은 이름의 site 자동 생성) */
@Entity
@Table(schema = "fac", name = "site")
@SQLRestriction("deleted_at IS NULL")
public class Site extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "owner_client_id", columnDefinition = "uuid")
    private UUID ownerClientId;

    protected Site() {}

    public static Site autoFor(String name, UUID ownerClientId) {
        var s = new Site();
        s.name = name;
        s.ownerClientId = ownerClientId;
        return s;
    }

    public String getName() {
        return name;
    }

    public UUID getOwnerClientId() {
        return ownerClientId;
    }
}
