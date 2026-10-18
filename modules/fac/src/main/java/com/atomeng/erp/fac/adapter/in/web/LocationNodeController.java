package com.atomeng.erp.fac.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc;
import com.atomeng.erp.fac.application.BuildingService;
import com.atomeng.erp.fac.application.LocationNodeService;
import com.atomeng.erp.fac.domain.location.MemberCode.MemberType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 위치 계층 API (설계 02 B4: GET/POST /buildings/{id}/nodes, POST /nodes:generate) + 건축물대장 조회.
 */
@RestController
@RequestMapping("/api/v1/fac")
class LocationNodeController {

    record GenerateRequest(int floorFrom, int floorTo,
                           @NotNull @Size(min = 2, max = 60) List<@Pattern(regexp = "^[A-Za-z]{1,2}\\d{1,3}$") String> xGrid,
                           @NotNull @Size(min = 2, max = 60) List<@Pattern(regexp = "^[A-Za-z]{1,2}\\d{1,3}$") String> yGrid,
                           Set<MemberType> memberTypes) {}

    record NodeRequest(UUID parentId, @NotNull @Pattern(regexp = "FLOOR|ZONE|MEMBER") String nodeType,
                       MemberType memberType, Short floorLevel, @Size(max = 60) String code, @Size(max = 200) String name,
                       @Size(max = 40) String gridRef) {}

    record MoveRequest(@NotNull UUID newParentId) {}

    /** 트리 응답 노드 */
    record TreeNode(UUID id, String nodeType, String memberType, String code, String name, Short floorLevel,
                    String gridRef, String path, List<TreeNode> children) {}

    private final LocationNodeService nodes;
    private final BuildingService buildings;

    LocationNodeController(LocationNodeService nodes, BuildingService buildings) {
        this.nodes = nodes;
        this.buildings = buildings;
    }

    /** 등록 마법사 1~2단계: 주소 정규화 + 건축물대장 표제부 목록 (장애 시 failureReason → 수기 입력) */
    @GetMapping("/register-lookup")
    @RequirePermission("fac:building:create")
    public BuildingService.LookupView lookup(@RequestParam String address) {
        return buildings.lookup(address);
    }

    /** ltree 1쿼리 조회 → 트리 조립. depth=1 이면 층까지 */
    @GetMapping("/buildings/{id}/nodes")
    @RequirePermission("fac:node:read")
    public List<TreeNode> tree(@PathVariable UUID id, @RequestParam(required = false) Integer depth) {
        List<NodeJdbc.Node> flat = nodes.tree(id, depth);
        Map<UUID, TreeNode> byId = new LinkedHashMap<>();
        List<TreeNode> roots = new ArrayList<>();
        for (var n : flat) { // 1차: 노드 생성 (층 순서 → 경로 순서)
            byId.put(n.id(), new TreeNode(n.id(), n.nodeType(), n.memberType(), n.code(), n.name(), n.floorLevel(),
                    n.gridRef(), n.path(), new ArrayList<>()));
        }
        for (var n : flat) { // 2차: 부모 연결 (이동된 노드도 안전)
            TreeNode t = byId.get(n.id());
            TreeNode parent = n.parentId() == null ? null : byId.get(n.parentId());
            if (parent == null) {
                roots.add(t);
            } else {
                parent.children().add(t);
            }
        }
        return roots;
    }

    @PostMapping("/buildings/{id}/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fac:node:write")
    public NodeJdbc.Node create(@PathVariable UUID id, @Valid @RequestBody NodeRequest r) {
        return nodes.create(id, new LocationNodeService.NodeCommand(r.parentId(), r.nodeType(), r.memberType(),
                r.floorLevel(), r.code(), r.name(), r.gridRef()));
    }

    @PostMapping("/buildings/{id}/nodes:generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fac:node:write")
    public Map<String, Integer> generate(@PathVariable UUID id, @Valid @RequestBody GenerateRequest r) {
        int n = nodes.generate(id, new LocationNodeService.GridTemplate(r.floorFrom(), r.floorTo(), r.xGrid(),
                r.yGrid(), r.memberTypes()));
        return Map.of("created", n);
    }

    @PostMapping("/buildings/{id}/nodes/{nodeId}:move")
    @RequirePermission("fac:node:write")
    public NodeJdbc.Node move(@PathVariable UUID id, @PathVariable UUID nodeId, @Valid @RequestBody MoveRequest r) {
        return nodes.move(id, nodeId, r.newParentId());
    }

    /** 논리삭제 — 결함 참조가 있으면 replacedById 필수 (BR-FAC-010, TC-FAC-008) */
    @DeleteMapping("/buildings/{id}/nodes/{nodeId}")
    @RequirePermission("fac:node:write")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID nodeId,
                                       @RequestParam(required = false) UUID replacedById) {
        nodes.delete(id, nodeId, replacedById);
        return ResponseEntity.noContent().build();
    }
}
