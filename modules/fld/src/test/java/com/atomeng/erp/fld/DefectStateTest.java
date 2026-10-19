package com.atomeng.erp.fld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.fld.domain.DefectState;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.fld.domain.StoredEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 05-IMPL Step 3 리듀서 단위 시험 — LWW(HLC) · 잠정 측정 미반영 · remove-wins · AI 제안 집합 · 수렴성(무작위 순서 1,000가지).
 * 모든 데이터는 합성값.
 */
class DefectStateTest {

    static final ObjectMapper OM = new ObjectMapper();
    static final UUID DEFECT = UUID.randomUUID();
    static final UUID ROUND = UUID.randomUUID();
    static final UUID ZONE = UUID.randomUUID();
    static final UUID DEV_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    static final UUID DEV_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    static final UUID ACTOR = UUID.randomUUID();
    static long seq;

    static String hlc(long wall, int c, String node) {
        return new Hlc(wall, c, node).pack();
    }

    static StoredEvent ev(String type, String hlc, UUID device, String payloadJson) throws Exception {
        seq++;
        return new StoredEvent(seq, UUID.randomUUID(), DEFECT, ROUND, ZONE, type, OM.readTree(payloadJson), hlc, device,
                ACTOR, false, Instant.ofEpochMilli(1_760_000_000_000L + seq));
    }

    static StoredEvent created() throws Exception {
        return ev("DefectCreated", hlc(1000, 0, "a"), DEV_A, """
                {"zoneId":"%s","typeCode":"CRK","subtypeCode":"FLEXURE",
                 "geomNorm":{"type":"LineString","coords":[[0.1,0.2],[0.3,0.4]]},"_server":{"code":"CRK-0001"}}"""
                .formatted(ZONE));
    }

    static String measured(String item, String value, boolean provisional) {
        return """
                {"item":"%s","value":%s,"unit":"mm","method":"INSTRUMENT","equipmentId":"%s",
                 "measuredAt":"2026-10-15T10:21:00+09:00","_server":{"measurementId":"%s","provisional":%s}}"""
                .formatted(item, value, UUID.randomUUID(), UUID.randomUUID(), provisional);
    }

    @Test
    @DisplayName("HLC: 고정 폭 문자열 사전순 = 시간순, successor 는 항상 더 늦다, 형식 오류 거부")
    void hlcOrdering() {
        assertThat(Hlc.compare(hlc(1000, 9, "z"), hlc(1001, 0, "a"))).isNegative();
        assertThat(Hlc.compare(hlc(1000, 1, "a"), hlc(1000, 2, "a"))).isNegative();
        Hlc h = Hlc.parse("0001728950400123:0003:d7f3");
        assertThat(h.successor(0, "srv").compareTo(h)).isPositive();
        assertThat(h.successor(1728950400123L + 5, "srv").wallMs()).isEqualTo(1728950400128L);
        assertThat(h.pack()).isEqualTo("0001728950400123:0003:d7f3");
        assertThat(Hlc.valid("1728950400123:3:d7f3")).isFalse();
        assertThatThrownBy(() -> Hlc.parse("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("측정 필수값(폭 · 길이) 완비 → RECORDED, 늦게 도착한 옛 HLC 값은 무시(LWW)")
    void lwwAndCompleteness() throws Exception {
        DefectState s = new DefectState(DEFECT);
        s.apply(created());
        assertThat(s.status()).isEqualTo("DRAFT");
        s.apply(ev("DefectMeasured", hlc(2000, 0, "a"), DEV_A, measured("width_mm", "0.30", false)));
        s.apply(ev("DefectMeasured", hlc(2000, 1, "a"), DEV_A, measured("length_m", "2.4", false)));
        assertThat(s.status()).isEqualTo("RECORDED");
        // 오프라인 기기 B 의 더 이른 HLC 값이 나중에 도착 → 무시
        s.apply(ev("DefectMeasured", hlc(1500, 0, "b"), DEV_B, measured("width_mm", "0.10", false)));
        assertThat(s.attrs().get("width_mm").decimalValue()).isEqualByComparingTo("0.30");
        assertThat(s.versionVector()).containsKeys(DEV_A.toString(), DEV_B.toString());
        assertThat(s.version()).isEqualTo(4);
    }

    @Test
    @DisplayName("TC-FLD-014(리듀서): 서버 재검증 실패(잠정) 측정은 attrs 미반영 → 필수값 미완 DRAFT")
    void provisionalMeasurementNotApplied() throws Exception {
        DefectState s = new DefectState(DEFECT);
        s.apply(created());
        s.apply(ev("DefectMeasured", hlc(2000, 0, "a"), DEV_A, measured("width_mm", "0.30", true)));
        s.apply(ev("DefectMeasured", hlc(2000, 1, "a"), DEV_A, measured("length_m", "2.4", false)));
        assertThat(s.attrs().has("width_mm")).isFalse();
        assertThat(s.status()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("BR-FLD-006 remove-wins · AI 제안(첨부 − 결정) · 사진 LWW · 검수 LWW")
    void removeWinsAiPhotos() throws Exception {
        DefectState s = new DefectState(DEFECT);
        s.apply(created());
        s.apply(ev("DefectMeasured", hlc(2000, 0, "a"), DEV_A, measured("width_mm", "0.30", false)));
        s.apply(ev("DefectMeasured", hlc(2000, 1, "a"), DEV_A, measured("length_m", "2.4", false)));
        UUID sug = UUID.randomUUID();
        s.apply(ev("AiSuggestionAttached", hlc(3000, 0, "srv"), DEV_A, "{\"suggestionId\":\"" + sug + "\"}"));
        assertThat(s.status()).isEqualTo("AI_SUGGESTED");
        s.apply(ev("AiSuggestionDecided", hlc(3001, 0, "a"), DEV_A,
                "{\"suggestionId\":\"" + sug + "\",\"decision\":\"REJECTED\"}"));
        assertThat(s.status()).isEqualTo("RECORDED");
        UUID photo = UUID.randomUUID();
        s.apply(ev("PhotoAttached", hlc(3100, 0, "a"), DEV_A, "{\"photoId\":\"" + photo + "\"}"));
        s.apply(ev("PhotoDetached", hlc(3050, 0, "b"), DEV_B, "{\"photoId\":\"" + photo + "\"}"));   // 옛 분리 → 무시
        assertThat(s.attachedPhotos()).containsExactly(photo.toString());
        s.apply(ev("DefectVerified", hlc(4000, 0, "srv"), DEV_A, "{\"zoneId\":\"" + ZONE + "\"}"));
        assertThat(s.status()).isEqualTo("VERIFIED");
        s.apply(ev("DefectRemoved", hlc(4100, 0, "a"), DEV_A, "{\"reason\":\"중복 기록\"}"));
        s.apply(ev("DefectUnverified", hlc(4200, 0, "srv"), DEV_A, "{\"reason\":\"잠금 해제 재조사\"}"));
        s.apply(ev("DefectMeasured", hlc(5000, 0, "b"), DEV_B, measured("width_mm", "0.50", false)));
        assertThat(s.status()).isEqualTo("REMOVED");
        assertThat(s.removedReason()).isEqualTo("중복 기록");
    }

    @Test
    @DisplayName("05-IMPL Step 3 수렴성: 같은 이벤트 집합을 1,000가지 무작위 순서로 적용해도 최종 내용 동일(측정 필드 포함)")
    void convergence() throws Exception {
        Random rnd = new Random(20261019L);
        StoredEvent create = created();
        List<StoredEvent> rest = new ArrayList<>();
        String[] items = {"width_mm", "length_m", "depth_mm"};
        UUID[] devices = {DEV_A, DEV_B};
        List<UUID> photos = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < 60; i++) {
            UUID dev = devices[rnd.nextInt(2)];
            String node = dev == DEV_A ? "a" : "b";
            String h = hlc(2000 + rnd.nextInt(500), i, node);   // 기기별 HLC 는 유일(같은 wall 이면 counter 증가)
            switch (rnd.nextInt(6)) {
                case 0 -> rest.add(ev("DefectMeasured", h, dev,
                        measured(items[rnd.nextInt(3)], "0." + (10 + rnd.nextInt(80)), rnd.nextInt(5) == 0)));
                case 1 -> rest.add(ev("DefectAttributesSet", h, dev,
                        "{\"fields\":{\"memo\":\"합성 메모 " + rnd.nextInt(100) + "\",\"direction\":\"V\"}}"));
                case 2 -> rest.add(ev("DefectGeometryChanged", h, dev, "{\"geomNorm\":{\"type\":\"LineString\","
                        + "\"coords\":[[0." + rnd.nextInt(9) + ",0.2],[0.3,0.4]]}}"));
                case 3 -> rest.add(ev(rnd.nextBoolean() ? "PhotoAttached" : "PhotoDetached", h, dev,
                        "{\"photoId\":\"" + photos.get(rnd.nextInt(3)) + "\"}"));
                case 4 -> rest.add(ev("DefectClassified", h, dev, "{\"typeCode\":\"CRK\",\"subtypeCode\":\"S"
                        + rnd.nextInt(4) + "\"}"));
                default -> rest.add(ev("VoiceMemoAttached", h, dev, "{\"voiceMemoId\":\"" + UUID.randomUUID()
                        + "\",\"transcript\":\"합성 음성\"}"));
            }
        }
        Map<String, Object> expected = replay(create, rest).contentView();
        for (int k = 0; k < 1000; k++) {
            List<StoredEvent> shuffled = new ArrayList<>(rest);
            Collections.shuffle(shuffled, rnd);
            assertThat(replay(create, shuffled).contentView()).isEqualTo(expected);
        }
    }

    private static DefectState replay(StoredEvent create, List<StoredEvent> rest) {
        DefectState s = new DefectState(DEFECT);
        s.apply(create);
        rest.forEach(s::apply);
        return s;
    }

    @Test
    @DisplayName("알 수 없는 이벤트 유형은 리듀서가 거부")
    void unknownEvent() throws Exception {
        ObjectNode p = OM.createObjectNode();
        StoredEvent e = new StoredEvent(1, UUID.randomUUID(), DEFECT, ROUND, ZONE, "DefectExploded", p, hlc(1, 0, "a"),
                DEV_A, ACTOR, false, Instant.now());
        assertThatThrownBy(() -> new DefectState(DEFECT).apply(e)).isInstanceOf(IllegalArgumentException.class);
    }
}
