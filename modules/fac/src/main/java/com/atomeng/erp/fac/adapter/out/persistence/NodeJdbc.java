package com.atomeng.erp.fac.adapter.out.persistence;

import com.atomeng.common.core.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** fac.location_node (ltree) JDBC 저장소 — 트리 1쿼리 조회, 배치 삽입, 서브트리 이동 */
@Repository
public class NodeJdbc {

    public record Node(UUID id, UUID buildingId, UUID parentId, String nodeType, String memberType, String code,
                       String name, Short floorLevel, String gridRef, String path, int depth, int sortOrder,
                       UUID replacedById, boolean deleted) {}

    public record NewNode(UUID id, UUID buildingId, UUID parentId, String nodeType, String memberType, String code,
                          String name, Short floorLevel, String gridRef, String path, int sortOrder) {
        public static NewNode of(UUID buildingId, UUID parentId, String nodeType, String memberType, String code,
                                 String name, Short floorLevel, String gridRef, String path, int sortOrder) {
            return new NewNode(UuidV7.next(), buildingId, parentId, nodeType, memberType, code, name, floorLevel,
                    gridRef, path, sortOrder);
        }
    }

    private static final String COLS = "id, building_id, parent_id, node_type, member_type, code, name, floor_level, "
            + "grid_ref, path::text AS path, nlevel(path) AS depth, sort_order, replaced_by_id, deleted_at";

    private final JdbcTemplate jdbc;

    public NodeJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Node> tree(UUID buildingId, Integer maxDepth) {
        return jdbc.query("SELECT " + COLS + " FROM fac.location_node WHERE building_id = ? AND deleted_at IS NULL"
                + (maxDepth == null ? "" : " AND nlevel(path) <= " + (maxDepth + 1))
                + " ORDER BY floor_level NULLS LAST, path, sort_order", this::map, buildingId);
    }

    public Optional<Node> find(UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM fac.location_node WHERE id = ?", this::map, id).stream().findFirst();
    }

    public Optional<Node> findFloor(UUID buildingId, int level) {
        return jdbc.query("SELECT " + COLS + " FROM fac.location_node WHERE building_id = ? AND node_type = 'FLOOR' "
                + "AND floor_level = ? AND deleted_at IS NULL", this::map, buildingId, level).stream().findFirst();
    }

    public int insertAll(List<NewNode> rows, UUID actor) {
        int[][] r = jdbc.batchUpdate("""
                INSERT INTO fac.location_node(id, building_id, parent_id, node_type, member_type, code, name, floor_level,
                                              grid_ref, path, sort_order, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::public.ltree, ?, ?, ?)""", rows, 500, (ps, n) -> {
            ps.setObject(1, n.id());
            ps.setObject(2, n.buildingId());
            ps.setObject(3, n.parentId());
            ps.setString(4, n.nodeType());
            ps.setString(5, n.memberType());
            ps.setString(6, n.code());
            ps.setString(7, n.name());
            ps.setObject(8, n.floorLevel());
            ps.setString(9, n.gridRef());
            ps.setString(10, n.path());
            ps.setInt(11, n.sortOrder());
            ps.setObject(12, actor);
            ps.setObject(13, actor);
        });
        int total = 0;
        for (int[] batch : r) {
            total += batch.length;
        }
        return total;
    }

    public int activeChildren(UUID nodeId) {
        return jdbc.queryForObject("SELECT count(*) FROM fac.location_node WHERE parent_id = ? AND deleted_at IS NULL",
                Integer.class, nodeId);
    }

    public int defectRefs(UUID nodeId) {
        return jdbc.queryForObject("SELECT count(*) FROM fac.node_defect_ref WHERE node_id = ?", Integer.class, nodeId);
    }

    /** 결함 참조 읽기 모델 갱신 (fld 이벤트) */
    public void upsertDefectRef(UUID nodeId, UUID defectId) {
        jdbc.update("""
                INSERT INTO fac.node_defect_ref(node_id, defect_id) VALUES (?, ?)
                ON CONFLICT (node_id, defect_id) DO UPDATE SET updated_at = now()""", nodeId, defectId);
    }

    public void moveDefectRefs(UUID fromNode, UUID toNode) {
        jdbc.update("""
                INSERT INTO fac.node_defect_ref(node_id, defect_id)
                SELECT ?, defect_id FROM fac.node_defect_ref WHERE node_id = ? ON CONFLICT DO NOTHING""", toNode, fromNode);
        jdbc.update("DELETE FROM fac.node_defect_ref WHERE node_id = ?", fromNode);
    }

    public void softDelete(UUID nodeId, UUID replacedById, UUID actor) {
        jdbc.update("""
                UPDATE fac.location_node SET deleted_at = now(), replaced_by_id = ?, updated_at = now(), updated_by = ?,
                       version = version + 1 WHERE id = ?""", replacedById, actor, nodeId);
    }

    /** 서브트리 이동: 새 부모 경로 || 기존 경로의 자기 라벨 이후 */
    public int moveSubtree(UUID nodeId, UUID newParentId, String oldPath, String newParentPath, UUID actor) {
        int n = jdbc.update("""
                UPDATE fac.location_node
                   SET path = ?::public.ltree || subpath(path, nlevel(?::public.ltree) - 1),
                       updated_at = now(), updated_by = ?, version = version + 1
                 WHERE path <@ ?::public.ltree AND building_id = (SELECT building_id FROM fac.location_node WHERE id = ?)""",
                newParentPath, oldPath, actor, oldPath, nodeId);
        jdbc.update("UPDATE fac.location_node SET parent_id = ? WHERE id = ?", newParentId, nodeId);
        return n;
    }

    // --------------------------------------------------------------- 병합 (Step 5)
    /** source 노드를 target 으로: 코드 충돌 시 "-M1" 접미사, 경로 루트 라벨 교체 */
    public int reparentForMerge(UUID sourceId, UUID targetId, String targetLabel, UUID actor) {
        jdbc.update("""
                UPDATE fac.location_node s
                   SET code = s.code || '-M1',
                       path = subpath(s.path, 0, nlevel(s.path) - 1)
                              || text2ltree(ltree2text(subpath(s.path, nlevel(s.path) - 1)) || '_m1')
                 WHERE s.building_id = ? AND s.deleted_at IS NULL
                   AND EXISTS (SELECT 1 FROM fac.location_node t
                                WHERE t.building_id = ? AND t.code = s.code AND t.deleted_at IS NULL)""",
                sourceId, targetId);
        return jdbc.update("""
                UPDATE fac.location_node
                   SET building_id = ?, path = text2ltree(?) || subpath(path, 1),
                       updated_at = now(), updated_by = ?, version = version + 1
                 WHERE building_id = ?""", targetId, targetLabel, actor, sourceId);
    }

    private Node map(ResultSet rs, int i) throws SQLException {
        Object fl = rs.getObject("floor_level");
        return new Node(rs.getObject("id", UUID.class), rs.getObject("building_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("node_type"), rs.getString("member_type"),
                rs.getString("code"), rs.getString("name"), fl == null ? null : ((Number) fl).shortValue(),
                rs.getString("grid_ref"), rs.getString("path"), rs.getInt("depth"), rs.getInt("sort_order"),
                rs.getObject("replaced_by_id", UUID.class), rs.getTimestamp("deleted_at") != null);
    }
}
