package com.atomeng.erp.fac.domain.model;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.jpa.BaseEntity;
import com.atomeng.erp.fac.domain.model.FacTypes.BuildingStatus;
import com.atomeng.erp.fac.domain.model.FacTypes.DataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

/**
 * 건축물(동) — 시설물 정본 (설계 02 B2·B3). 상태 전이(A6.1)를 엔티티 메서드로 캡슐화(02-IMPL Step 2).
 * 공간 좌표(location)는 PostGIS 프로필 마이그레이션 컬럼이라 엔티티에 매핑하지 않는다.
 */
@Entity
@Table(schema = "fac", name = "building")
@SQLRestriction("deleted_at IS NULL")
public class Building extends BaseEntity {

    public static final String INVALID_TRANSITION = "FAC-409-002";

    @Column(name = "site_id", nullable = false, columnDefinition = "uuid")
    private UUID siteId;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "dong_name", nullable = false, length = 100)
    private String dongName = "";
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 19)
    private String pnu;
    @Column(name = "road_address", length = 300)
    private String roadAddress;
    @Column(name = "jibun_address", length = 300)
    private String jibunAddress;
    @Column(name = "main_purpose_nm", length = 100)
    private String mainPurposeNm;
    @Column(name = "structure_nm", length = 100)
    private String structureNm;
    @Column(name = "gross_floor_area", precision = 14, scale = 2)
    private BigDecimal grossFloorArea;
    @Column(name = "floors_above")
    private Short floorsAbove;
    @Column(name = "floors_below")
    private Short floorsBelow;
    @Column(name = "approval_date")
    private LocalDate approvalDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BuildingStatus status = BuildingStatus.DRAFT;
    @Enumerated(EnumType.STRING)
    @Column(name = "data_source", nullable = false, length = 20)
    private DataSource dataSource = DataSource.REGISTER;
    @Column(name = "merged_into_id", columnDefinition = "uuid")
    private UUID mergedIntoId;
    @Column(name = "use_restriction_flag", nullable = false)
    private boolean useRestrictionFlag;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "register_snapshot", columnDefinition = "jsonb")
    private String registerSnapshot;
    @Column(name = "register_synced_at")
    private java.time.Instant registerSyncedAt;

    protected Building() {}

    public record Spec(String name, String dongName, String pnu, String roadAddress, String jibunAddress,
                       String mainPurposeNm, String structureNm, BigDecimal grossFloorArea, Short floorsAbove,
                       Short floorsBelow, LocalDate approvalDate, DataSource dataSource) {}

    public static Building register(UUID siteId, Spec s) {
        var b = new Building();
        b.siteId = siteId;
        b.apply(s);
        b.dataSource = s.dataSource() == null ? DataSource.MANUAL : s.dataSource();
        return b;
    }

    /** PATCH — null 은 변경 없음 */
    public void change(Spec s) {
        if (status == BuildingStatus.MERGED || status == BuildingStatus.DEMOLISHED) {
            throw new DomainException(INVALID_TRANSITION, "병합·해체된 시설물은 수정할 수 없습니다(" + status + ")");
        }
        apply(s);
    }

    private void apply(Spec s) {
        if (s.name() != null) {
            name = s.name().trim();
        }
        if (s.dongName() != null) {
            dongName = s.dongName().trim();
        }
        if (s.pnu() != null) {
            pnu = s.pnu();
        }
        if (s.roadAddress() != null) {
            roadAddress = s.roadAddress();
        }
        if (s.jibunAddress() != null) {
            jibunAddress = s.jibunAddress();
        }
        if (s.mainPurposeNm() != null) {
            mainPurposeNm = s.mainPurposeNm();
        }
        if (s.structureNm() != null) {
            structureNm = s.structureNm();
        }
        if (s.grossFloorArea() != null) {
            grossFloorArea = s.grossFloorArea();
        }
        if (s.floorsAbove() != null) {
            floorsAbove = s.floorsAbove();
        }
        if (s.floorsBelow() != null) {
            floorsBelow = s.floorsBelow();
        }
        if (s.approvalDate() != null) {
            approvalDate = s.approvalDate();
        }
    }

    /** 건축물대장 원본 스냅샷 보존 (FR-FAC-002) */
    public void attachRegisterSnapshot(String json, java.time.Instant syncedAt) {
        this.registerSnapshot = json;
        this.registerSyncedAt = syncedAt;
        this.dataSource = DataSource.REGISTER;
    }

    // --- 상태 전이 (설계 A6.1) ---
    public void verify() {
        transition(EnumSet.of(BuildingStatus.DRAFT), BuildingStatus.VERIFIED);
    }

    /** PM 분류 확정. 이미 ACTIVE 면 상태 유지(재분류) */
    public void classify() {
        if (status == BuildingStatus.ACTIVE) {
            return;
        }
        transition(EnumSet.of(BuildingStatus.DRAFT, BuildingStatus.VERIFIED, BuildingStatus.RECLASSIFY_REQUIRED,
                BuildingStatus.CLASSIFIED), BuildingStatus.CLASSIFIED);
    }

    public void activate() {
        transition(EnumSet.of(BuildingStatus.CLASSIFIED), BuildingStatus.ACTIVE);
    }

    public void markMerged(UUID targetId) {
        transition(EnumSet.of(BuildingStatus.DRAFT, BuildingStatus.VERIFIED, BuildingStatus.CLASSIFIED,
                BuildingStatus.ACTIVE), BuildingStatus.MERGED);
        mergedIntoId = targetId;
    }

    public void markDemolished() {
        transition(EnumSet.of(BuildingStatus.ACTIVE, BuildingStatus.CLASSIFIED), BuildingStatus.DEMOLISHED);
    }

    /** BR-FAC-011 */
    public void flagUseRestriction() {
        useRestrictionFlag = true;
    }

    private void transition(EnumSet<BuildingStatus> from, BuildingStatus to) {
        if (!from.contains(status)) {
            throw new DomainException(INVALID_TRANSITION, "시설물 상태 전이 불가: %s → %s".formatted(status, to));
        }
        status = to;
    }

    public UUID getSiteId() { return siteId; }
    public String getName() { return name; }
    public String getDongName() { return dongName; }
    public String getPnu() { return pnu; }
    public String getRoadAddress() { return roadAddress; }
    public String getJibunAddress() { return jibunAddress; }
    public String getMainPurposeNm() { return mainPurposeNm; }
    public String getStructureNm() { return structureNm; }
    public BigDecimal getGrossFloorArea() { return grossFloorArea; }
    public Short getFloorsAbove() { return floorsAbove; }
    public Short getFloorsBelow() { return floorsBelow; }
    public LocalDate getApprovalDate() { return approvalDate; }
    public BuildingStatus getStatus() { return status; }
    public DataSource getDataSource() { return dataSource; }
    public UUID getMergedIntoId() { return mergedIntoId; }
    public boolean isUseRestrictionFlag() { return useRestrictionFlag; }
    public String getRegisterSnapshot() { return registerSnapshot; }
}
