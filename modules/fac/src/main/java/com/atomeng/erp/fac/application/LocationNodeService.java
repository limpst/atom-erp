package com.atomeng.erp.fac.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc.NewNode;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc.Node;
import com.atomeng.erp.fac.domain.location.MemberCode;
import com.atomeng.erp.fac.domain.location.MemberCode.MemberType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 위치 계층 site→building→floor→zone→member (02-IMPL Step 7, FR-FAC-005·006, BR-FAC-010).
 * 경로는 ltree: b_{building}.f_{층}.z_{구역}.m_{부재코드}
 */
@Service
public class LocationNodeService {

    public static final String REPLACEMENT_REQUIRED = "FAC-422-002";
    public static final String NODE_NOT_FOUND = "FAC-404-002";
    public static final String NODE_INVALID = "FAC-422-003";

    /**
     * 그리드 템플릿 (설계 B7.3). 층마다 FLOOR(없으면 생성) → ZONE 1개(전체 그리드, 3×3 베이 분할은 후속) →
     * 교차점 COL, 인접 교차점 사이 GIR(X·Y 방향), 베이마다 SLAB.
     */
    public record GridTemplate(int floorFrom, int floorTo, List<String> xGrid, List<String> yGrid,
                               Set<MemberType> memberTypes) {}

    public record NodeCommand(UUID parentId, String nodeType, MemberType memberType, Short floorLevel, String code,
                              String name, String gridRef) {}

    private final NodeJdbc nodes;
    private final BuildingRepository buildings;
    private final AuditLog audit;
    private final CurrentActor actor;

    public LocationNodeService(NodeJdbc nodes, BuildingRepository buildings, AuditLog audit, CurrentActor actor) {
        this.nodes = nodes;
        this.buildings = buildings;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional(readOnly = true)
    public List<Node> tree(UUID buildingId, Integer depth) {
        ensureBuilding(buildingId);
        return nodes.tree(buildingId, depth);
    }

    /** 건축물대장 층수로 FLOOR 노드 생성 (등록 시, 02-IMPL Step 4) */
    @Transactional
    public int createFloors(UUID buildingId, Short floorsBelow, Short floorsAbove) {
        List<NewNode> rows = new ArrayList<>();
        int below = floorsBelow == null ? 0 : floorsBelow;
        int above = floorsAbove == null ? 0 : floorsAbove;
        int order = 0;
        for (int lvl = -below; lvl <= above; lvl++) {
            if (lvl == 0 || nodes.findFloor(buildingId, lvl).isPresent()) {
                continue;
            }
            rows.add(floorRow(buildingId, lvl, order++));
        }
        return rows.isEmpty() ? 0 : nodes.insertAll(rows, actor.actorIdOrSystem());
    }

    @Transactional
    public int generate(UUID buildingId, GridTemplate t) {
        ensureBuilding(buildingId);
        if (t.xGrid() == null || t.xGrid().size() < 2 || t.yGrid() == null || t.yGrid().size() < 2) {
            throw new DomainException(NODE_INVALID, "X·Y 그리드는 각각 2개 이상");
        }
        Set<MemberType> types = t.memberTypes() == null || t.memberTypes().isEmpty()
                ? EnumSet.of(MemberType.COL, MemberType.GIR, MemberType.SLAB) : t.memberTypes();
        UUID by = actor.actorIdOrSystem();
        List<NewNode> rows = new ArrayList<>();
        int lo = Math.min(t.floorFrom(), t.floorTo());
        int hi = Math.max(t.floorFrom(), t.floorTo());
        for (int lvl = lo; lvl <= hi; lvl++) {
            if (lvl == 0) {
                continue;
            }
            UUID floorId;
            String floorPath;
            var floor = nodes.findFloor(buildingId, lvl);
            if (floor.isPresent()) {
                floorId = floor.get().id();
                floorPath = floor.get().path();
            } else {
                NewNode f = floorRow(buildingId, lvl, lvl);
                rows.add(f);
                floorId = f.id();
                floorPath = f.path();
            }
            String fl = MemberCode.floor(lvl);
            String zoneCode = fl + "-Z1";
            String zonePath = floorPath + ".z_1";
            NewNode zone = NewNode.of(buildingId, floorId, "ZONE", null, zoneCode,
                    "%s %s~%s / %s~%s".formatted(fl, first(t.xGrid()), last(t.xGrid()), first(t.yGrid()),
                            last(t.yGrid())), (short) lvl, null, zonePath, 0);
            rows.add(zone);
            int order = 0;
            var xs = t.xGrid();
            var ys = t.yGrid();
            for (int i = 0; i < xs.size(); i++) {
                for (int j = 0; j < ys.size(); j++) {
                    String x = xs.get(i);
                    String y = ys.get(j);
                    if (types.contains(MemberType.COL)) {
                        rows.add(member(buildingId, zone, MemberType.COL, MemberCode.column(lvl, x, y), x + y, lvl, order++));
                    }
                    if (types.contains(MemberType.GIR) && i + 1 < xs.size()) {
                        rows.add(member(buildingId, zone, MemberType.GIR,
                                MemberCode.girder(lvl, x, y, xs.get(i + 1), y), x + y + "-" + xs.get(i + 1) + y, lvl, order++));
                    }
                    if (types.contains(MemberType.GIR) && j + 1 < ys.size()) {
                        rows.add(member(buildingId, zone, MemberType.GIR,
                                MemberCode.girder(lvl, x, y, x, ys.get(j + 1)), x + y + "-" + x + ys.get(j + 1), lvl, order++));
                    }
                    if (types.contains(MemberType.SLAB) && i + 1 < xs.size() && j + 1 < ys.size()) {
                        rows.add(member(buildingId, zone, MemberType.SLAB,
                                MemberCode.slab(lvl, x, y, xs.get(i + 1), ys.get(j + 1)),
                                x + y + "-" + xs.get(i + 1) + ys.get(j + 1), lvl, order++));
                    }
                }
            }
        }
        int n = nodes.insertAll(rows, by);
        audit.append("FAC", AuditLog.Action.CREATE, "LocationNodeTemplate", buildingId, null,
                Map.of("rows", n, "floors", lo + ".." + hi, "x", t.xGrid(), "y", t.yGrid()));
        return n;
    }

    @Transactional
    public Node create(UUID buildingId, NodeCommand c) {
        ensureBuilding(buildingId);
        UUID by = actor.actorIdOrSystem();
        NewNode row;
        switch (c.nodeType()) {
            case "FLOOR" -> {
                if (c.floorLevel() == null) {
                    throw new DomainException(NODE_INVALID, "FLOOR 는 floorLevel 필요");
                }
                row = floorRow(buildingId, c.floorLevel(), c.floorLevel());
            }
            case "ZONE", "MEMBER" -> {
                Node parent = parentOf(buildingId, c.parentId());
                boolean zone = "ZONE".equals(c.nodeType());
                if (zone && !"FLOOR".equals(parent.nodeType())
                        || !zone && !Set.of("FLOOR", "ZONE").contains(parent.nodeType())) {
                    throw new DomainException(NODE_INVALID, c.nodeType() + " 의 부모로 " + parent.nodeType() + " 불가");
                }
                if (c.code() == null || c.code().isBlank()) {
                    throw new DomainException(NODE_INVALID, "code 필요");
                }
                String path = parent.path() + "." + MemberCode.label(zone ? "z" : "m", c.code());
                row = NewNode.of(buildingId, parent.id(), c.nodeType(),
                        zone ? null : (c.memberType() == null ? MemberType.ETC : c.memberType()).name(), c.code(),
                        c.name(), parent.floorLevel(), c.gridRef(), path, 0);
            }
            default -> throw new DomainException(NODE_INVALID, "nodeType: FLOOR|ZONE|MEMBER");
        }
        nodes.insertAll(List.of(row), by);
        return nodes.find(row.id()).orElseThrow();
    }

    /** 서브트리 이동 (부모 변경) */
    @Transactional
    public Node move(UUID buildingId, UUID nodeId, UUID newParentId) {
        Node n = nodeOf(buildingId, nodeId);
        Node p = parentOf(buildingId, newParentId);
        if (p.path().equals(n.path()) || p.path().startsWith(n.path() + ".")) {
            throw new DomainException(NODE_INVALID, "자기 하위로 이동할 수 없습니다");
        }
        nodes.moveSubtree(nodeId, p.id(), n.path(), p.path(), actor.actorIdOrSystem());
        return nodes.find(nodeId).orElseThrow();
    }

    /**
     * 논리삭제 (BR-FAC-010, TC-FAC-008). 결함 참조가 있으면 대체 member(replacedById) 필수 → 없으면 422 FAC-422-002.
     * 대체 지정 시 결함 참조 읽기 모델을 대체 노드로 옮긴다(FLD 는 fac 이벤트로 스스로 재매핑 — 후속).
     */
    @Transactional
    public void delete(UUID buildingId, UUID nodeId, UUID replacedById) {
        Node n = nodeOf(buildingId, nodeId);
        if (nodes.activeChildren(nodeId) > 0) {
            throw new DomainException(NODE_INVALID, "하위 노드가 있는 노드는 삭제할 수 없습니다");
        }
        int refs = nodes.defectRefs(nodeId);
        if (refs > 0 && replacedById == null) {
            throw new DomainException(REPLACEMENT_REQUIRED,
                    "결함 %d건이 참조하는 부재입니다. 대체 부재(replacedById)를 지정하세요".formatted(refs))
                    .with("defectRefs", refs);
        }
        if (replacedById != null) {
            Node r = nodeOf(buildingId, replacedById);
            if (r.id().equals(nodeId)) {
                throw new DomainException(NODE_INVALID, "자기 자신으로 대체할 수 없습니다");
            }
            nodes.moveDefectRefs(nodeId, r.id());
        }
        nodes.softDelete(nodeId, replacedById, actor.actorIdOrSystem());
        audit.append("FAC", AuditLog.Action.DELETE, "LocationNode", nodeId,
                Map.of("code", n.code(), "path", n.path()),
                replacedById == null ? null : Map.of("replacedById", replacedById.toString(), "defectRefs", refs));
    }

    private void ensureBuilding(UUID buildingId) {
        if (!buildings.existsById(buildingId)) {
            throw new DomainException(BuildingService.NOT_FOUND, "시설물 없음: " + buildingId);
        }
    }

    private Node nodeOf(UUID buildingId, UUID nodeId) {
        return nodes.find(nodeId).filter(n -> n.buildingId().equals(buildingId) && !n.deleted())
                .orElseThrow(() -> new DomainException(NODE_NOT_FOUND, "위치 노드 없음: " + nodeId));
    }

    private Node parentOf(UUID buildingId, UUID parentId) {
        if (parentId == null) {
            throw new DomainException(NODE_INVALID, "parentId 필요");
        }
        return nodeOf(buildingId, parentId);
    }

    private static NewNode floorRow(UUID buildingId, int lvl, int order) {
        return NewNode.of(buildingId, null, "FLOOR", null, MemberCode.floor(lvl), MemberCode.floor(lvl), (short) lvl,
                null, MemberCode.buildingLabel(buildingId) + "." + MemberCode.floorLabel(lvl), order);
    }

    private static NewNode member(UUID buildingId, NewNode zone, MemberType type, String code, String grid, int lvl,
                                  int order) {
        return NewNode.of(buildingId, zone.id(), "MEMBER", type.name(), code, null, (short) lvl, grid,
                zone.path() + "." + MemberCode.label("m", code), order);
    }

    private static String first(List<String> l) {
        return l.get(0);
    }

    private static String last(List<String> l) {
        return l.get(l.size() - 1);
    }
}
