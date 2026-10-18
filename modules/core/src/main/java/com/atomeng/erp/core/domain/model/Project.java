package com.atomeng.erp.core.domain.model;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/** 프로젝트 — core.project (설계 7.1, 00-IMPL Step 3 예시 엔티티) */
@Entity
@Table(schema = "core", name = "project")
@SQLRestriction("deleted_at IS NULL")
public class Project extends BaseEntity {

    @Column(name = "project_no", nullable = false, unique = true, length = 20)
    private String projectNo;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(name = "service_type", nullable = false, length = 30)
    private String serviceType;

    @Column(name = "client_id", nullable = false, columnDefinition = "uuid")
    private UUID clientId;

    @Column(name = "contract_id", columnDefinition = "uuid")
    private UUID contractId;

    @Column(name = "pm_id", columnDefinition = "uuid")
    private UUID pmId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.PLANNED;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "contract_amount", precision = 15, scale = 0)
    private BigDecimal contractAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "KRW";

    protected Project() {}

    /** SAL 계약에서 생성된 프로젝트의 계약 연결(논리 FK sal.contract.id) — 01-IMPL Step 9 */
    public void linkContract(UUID contractId, String currency) {
        this.contractId = contractId;
        if (currency != null) {
            this.currency = currency;
        }
    }

    public static Project create(String projectNo, String name, String serviceType, UUID clientId,
                                 LocalDate startDate, LocalDate endDate, BigDecimal contractAmount) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "종료일은 시작일 이후여야 합니다");
        }
        var p = new Project();
        p.projectNo = projectNo;
        p.name = name;
        p.serviceType = serviceType;
        p.clientId = clientId;
        p.startDate = startDate;
        p.endDate = endDate;
        p.contractAmount = contractAmount;
        return p;
    }

    public void transitionTo(ProjectStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new DomainException(CoreErrors.INVALID_STATE_TRANSITION,
                    "프로젝트 상태 전이 불가: %s → %s".formatted(status, next));
        }
        this.status = next;
    }

    public String getProjectNo() {
        return projectNo;
    }

    public String getName() {
        return name;
    }

    public String getServiceType() {
        return serviceType;
    }

    public UUID getClientId() {
        return clientId;
    }

    public UUID getPmId() {
        return pmId;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public BigDecimal getContractAmount() {
        return contractAmount;
    }

    public String getCurrency() {
        return currency;
    }
}
