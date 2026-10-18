package com.atomeng.erp.fac.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FLD 결함 기록 이벤트 fld.defect.recorded(스텁 계약: defectId, locationNodeId) → fac.node_defect_ref 읽기 모델.
 * BR-FAC-010 삭제 검사용. FLD 가 FAC 에 의존하므로 FAC 는 FLD API 를 호출하지 않고 이벤트로만 안다.
 */
@Component
public class DefectReferenceListener {

    public static final String CONSUMER = "fac.defect-ref";
    private static final Logger log = LoggerFactory.getLogger(DefectReferenceListener.class);

    private final NodeJdbc nodes;

    public DefectReferenceListener(NodeJdbc nodes) {
        this.nodes = nodes;
    }

    @EventListener(condition = "#env.eventType == 'fld.defect.recorded'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        String node = env.payload().path("locationNodeId").asText(null);
        String defect = env.payload().path("defectId").asText(null);
        if (node == null || defect == null) {
            log.warn("fld.defect.recorded 에 locationNodeId/defectId 없음 — 무시 event={}", env.eventId());
            return;
        }
        UUID nodeId = UUID.fromString(node);
        if (nodes.find(nodeId).isEmpty()) {
            log.warn("알 수 없는 위치 노드 {} — 무시", nodeId);
            return;
        }
        nodes.upsertDefectRef(nodeId, UUID.fromString(defect));
    }
}
