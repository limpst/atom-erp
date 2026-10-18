package com.atomeng.common.core.jpa;

import com.atomeng.common.core.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 모든 업무 테이블의 공통 6컬럼(설계 7.2 공통 컬럼 규약) + UUID v7 PK.
 * created_at, created_by, updated_at, updated_by, version, deleted_at
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    private UUID id = UuidV7.next();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Transient
    private boolean isNew = true; // persist() 유도 → merge 전 SELECT 생략

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /** 오프라인 기기에서 생성한 ID를 그대로 쓰는 경우(FLD 동기화, 설계 12장-1). */
    protected void assignClientId(UUID clientGeneratedId) {
        if (!UuidV7.isV7(clientGeneratedId)) {
            throw new IllegalArgumentException("UUID v7 required");
        }
        this.id = clientGeneratedId;
    }

    public void softDelete(Clock clock) {
        this.deletedAt = Instant.now(clock);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
