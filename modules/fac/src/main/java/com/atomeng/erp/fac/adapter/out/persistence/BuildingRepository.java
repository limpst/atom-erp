package com.atomeng.erp.fac.adapter.out.persistence;

import com.atomeng.erp.fac.domain.model.Building;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BuildingRepository extends JpaRepository<Building, UUID> {

    /** 중복 후보 투영 (설계 B7.2 — PostGIS 거리 조건은 postgis 프로필에서 확장) */
    interface DuplicateCandidate {
        UUID getId();
        String getName();
        Double getNameSimilarity();
        String getMatchedBy();
    }

    /**
     * BR-FAC-002 PNU+동명 완전 일치, 또는 같은 도로명주소 + 명칭 유사도 ≥ 0.85 (pg_trgm).
     * 네이티브 쿼리라 @SQLRestriction 이 적용되지 않으므로 deleted_at 조건을 직접 쓴다.
     */
    @Query(value = """
            SELECT b.id AS id, b.name AS name,
                   public.similarity(b.name, :name) AS nameSimilarity,
                   CASE WHEN b.pnu = :pnu AND b.dong_name = :dong THEN 'PNU_DONG' ELSE 'ADDR_NAME' END AS matchedBy
              FROM fac.building b
             WHERE b.deleted_at IS NULL AND b.status <> 'MERGED'
               AND ((CAST(:pnu AS text) IS NOT NULL AND b.pnu = :pnu AND b.dong_name = :dong)
                 OR (CAST(:road AS text) IS NOT NULL AND b.road_address = :road
                     AND public.similarity(b.name, :name) >= 0.85))
             ORDER BY matchedBy DESC, nameSimilarity DESC
             LIMIT 5""", nativeQuery = true)
    List<DuplicateCandidate> findDuplicateCandidates(@Param("pnu") String pnu, @Param("dong") String dong,
                                                     @Param("name") String name, @Param("road") String road);

    @Query("select b from Building b where (:q is null or lower(b.name) like lower(concat('%', cast(:q as string), '%'))) order by b.name")
    Page<Building> search(@Param("q") String q, Pageable pageable);
}
