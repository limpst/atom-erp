package com.atomeng.erp.qar;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.qar.domain.Command;
import com.atomeng.erp.qar.domain.CommentStatus;
import com.atomeng.erp.qar.domain.CommentStatus.ReplyAction;
import com.atomeng.erp.qar.domain.ReviewStatus;
import com.atomeng.erp.qar.domain.TransitionTable;
import com.atomeng.erp.qar.domain.ai.CitationGuard;
import com.atomeng.erp.qar.domain.ai.CitationGuard.Citation;
import com.atomeng.erp.qar.domain.ai.CitationGuard.Draft;
import com.atomeng.erp.qar.domain.anchor.AnchorResolver;
import com.atomeng.erp.qar.domain.autocheck.NumberExtractor;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 순수 도메인: 전이 표 · 코멘트 상태 · CitationGuard · 앵커 검증/재매핑 · 수치 추출 */
class DomainTest {

    @Test
    @DisplayName("B7.1 전이 표: 7 상태 × 9 명령 = 63 조합 중 허용 10건(AI_START · REOPEN_FOR_CORRECTION 포함), 나머지 QAR_STATE")
    void transitionTable() {
        int allowed = 0;
        for (ReviewStatus s : ReviewStatus.values()) {
            for (Command c : Command.values()) {
                if (TransitionTable.find(s, c).isPresent()) {
                    allowed++;
                }
            }
        }
        assertThat(ReviewStatus.values().length * Command.values().length).isEqualTo(63);
        assertThat(allowed).isEqualTo(10).isEqualTo(TransitionTable.all().size());
        assertThat(TransitionTable.find(ReviewStatus.SUBMITTED, Command.AI_START)).get()
                .extracting(TransitionTable.Transition::to).isEqualTo(ReviewStatus.AI_REVIEWING);
        assertThat(TransitionTable.find(ReviewStatus.APPROVED, Command.REOPEN_FOR_CORRECTION)).get()
                .satisfies(t -> {
                    assertThat(t.to()).isEqualTo(ReviewStatus.SELF_CHECK);
                    assertThat(t.guards()).containsExactly(TransitionTable.Guard.CORRECTION_REVISION_EXISTS);
                    assertThat(t.effects()).contains(TransitionTable.Effect.NEW_ROUND);
                });
        assertThat(TransitionTable.find(ReviewStatus.SELF_CHECK, Command.SUBMIT).orElseThrow().guards())
                .contains(TransitionTable.Guard.NO_BLOCKING, TransitionTable.Guard.CHECKLIST_SELF_COMPLETE);
        assertThat(TransitionTable.find(ReviewStatus.PENDING_APPROVAL, Command.APPROVE).orElseThrow().guards())
                .contains(TransitionTable.Guard.SOD_APPROVER, TransitionTable.Guard.QUALIFICATION_VALID);
        assertThat(TransitionTable.find(ReviewStatus.APPROVED, Command.APPROVE)).isEmpty();
        assertThat(TransitionTable.find(ReviewStatus.SELF_CHECK, Command.APPROVE)).isEmpty();
    }

    @Test
    @DisplayName("A6 코멘트 상태: OPEN → ANSWERED → RESOLUTION_REQUESTED → RESOLVED / 재오픈, 철회는 OPEN 만, 작성자 전용 동작")
    void commentStates() {
        assertThat(CommentStatus.OPEN.next(ReplyAction.ANSWER)).contains(CommentStatus.ANSWERED);
        assertThat(CommentStatus.ANSWERED.next(ReplyAction.REQUEST_RESOLUTION)).contains(CommentStatus.RESOLUTION_REQUESTED);
        assertThat(CommentStatus.RESOLUTION_REQUESTED.next(ReplyAction.CONFIRM)).contains(CommentStatus.RESOLVED);
        assertThat(CommentStatus.RESOLUTION_REQUESTED.next(ReplyAction.REOPEN)).contains(CommentStatus.OPEN);
        assertThat(CommentStatus.OPEN.next(ReplyAction.CONFIRM)).isEmpty();
        assertThat(CommentStatus.ANSWERED.next(ReplyAction.WITHDRAW)).isEmpty();
        assertThat(CommentStatus.RESOLVED.next(ReplyAction.ANSWER)).isEmpty();
        assertThat(CommentStatus.commentAuthorOnly(ReplyAction.CONFIRM)).isTrue();
        assertThat(CommentStatus.commentAuthorOnly(ReplyAction.REQUEST_RESOLUTION)).isFalse();
    }

    // ------------------------------------------------------------------ CitationGuard (TC-QAR-005)

    static final UUID B1 = UUID.randomUUID();
    static final UUID B2 = UUID.randomUUID();

    static CitationGuard.Context ctx(Set<UUID> anchors) throws Exception {
        var facts = new ObjectMapper().readTree("""
                {"quantityTable":[{"floorCode":"1F","memberType":"COLUMN","count":2,"lengthM":2.40}],
                 "grade":{"computed":"C","final":"C"},"defects":[{"id":"D-0001","widthMm":0.25}]}""");
        return new CitationGuard.Context(Map.of(B1, "1F 기둥 균열이 3개소   확인되었다.", B2, "표"), anchors, facts);
    }

    static Citation report(UUID block, String quote) {
        return new Citation("report", block, quote, null, null);
    }

    static Citation data(String ref, Object value) {
        return new Citation("data", null, null, ref, value);
    }

    @Test
    @DisplayName("TC-QAR-005: 보고서에 없는 인용문 · 없는 블록 · 앵커 없는 블록 · 값 불일치 · 근거 부족 → 거부 사유, 정상 인용(공백 정규화)만 통과")
    void citationGuard() throws Exception {
        var c = ctx(Set.of(B1, B2));
        assertThat(CitationGuard.guard(new Draft("NUM_MISMATCH", "MAJOR", "본문 3개소 vs 물량표 2개소",
                List.of(report(B1, "균열이 3개소 확인"), data("quantityTable[0].count", 2))), c).passed()).isTrue();
        assertThat(CitationGuard.guard(new Draft("NUM_MISMATCH", "MAJOR", "x",
                List.of(report(B1, "균열이 5개소"), data("quantityTable[0].count", 2))), c).reason()).isEqualTo("QUOTE_NOT_FOUND");
        assertThat(CitationGuard.guard(new Draft("LOGIC_GAP", "MINOR", "x", List.of(report(UUID.randomUUID(), "표"))), c)
                .reason()).isEqualTo("UNKNOWN_BLOCK");
        assertThat(CitationGuard.guard(new Draft("LOGIC_GAP", "MINOR", "x", List.of(report(B2, "표"))), ctx(Set.of(B1)))
                .reason()).isEqualTo("UNKNOWN_ANCHOR");
        assertThat(CitationGuard.guard(new Draft("NUM_MISMATCH", "MAJOR", "x",
                List.of(report(B1, "3개소"), data("quantityTable[0].count", 4))), c).reason()).isEqualTo("DATA_MISMATCH");
        assertThat(CitationGuard.guard(new Draft("NUM_MISMATCH", "MAJOR", "x", List.of(report(B1, "3개소"))), c).reason())
                .isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(CitationGuard.guard(new Draft("LOGIC_GAP", "MINOR", "x", List.of()), c).reason()).isEqualTo("NO_CITATION");
        assertThat(CitationGuard.guard(new Draft("LOGIC_GAP", "MINOR", "x", List.of(data("grade.final", "C"))), c).reason())
                .isEqualTo("NO_REPORT_CITATION");
        assertThat(CitationGuard.guard(new Draft("HALLUCINATION", "MINOR", "x", List.of(report(B1, "3개소"))), c).reason())
                .isEqualTo("INVALID_CATEGORY");
        // facts 경로: 배열 길이 · id 선택
        assertThat(CitationGuard.resolve(c.facts(), "defects.length")).get().extracting(n -> n.asInt()).isEqualTo(1);
        assertThat(CitationGuard.resolve(c.facts(), "defects[D-0001].widthMm")).get()
                .extracting(n -> n.decimalValue()).isEqualTo(new BigDecimal("0.25"));
        // 렌더 전(anchors.json 없음) — 블록 존재만 확인
        assertThat(CitationGuard.guard(new Draft("LOGIC_GAP", "MINOR", "x", List.of(report(B2, "표"))), ctx(null)).passed())
                .isTrue();
    }

    // ------------------------------------------------------------------ 앵커 (TC-QAR-014 · 007)

    static com.atomeng.erp.rpt.api.RptRevisionQuery.Block blk(UUID id, String text) {
        return Fixtures.block(id, "ch7.opinion", 1, "PARAGRAPH", text, false, "HUMAN", List.of());
    }

    @Test
    @DisplayName("TC-QAR-014(도메인): TEXT_RANGE 앵커는 블록 원문 [start, end) = quote 여야 하고, start/end 없으면 quote 위치로 계산")
    void anchorValidation() {
        var b = blk(B1, "지하1층 기둥 C-12에서 폭 0.3mm 수직균열이 3개소 확인되었다.");
        Map<String, Object> a = new LinkedHashMap<>(Map.of("type", "TEXT_RANGE", "blockId", B1.toString(),
                "start", 15, "end", 28, "quote", "폭 0.3mm 수직균열이"));
        assertThat(AnchorResolver.validateTextRange(a, b)).isPresent();
        a.put("quote", "폭 0.4mm 수직균열이");
        assertThat(AnchorResolver.validateTextRange(a, b)).isEmpty();
        Map<String, Object> noOffsets = new LinkedHashMap<>(Map.of("type", "TEXT_RANGE", "quote", "3개소"));
        assertThat(AnchorResolver.validateTextRange(noOffsets, b)).get()
                .satisfies(x -> assertThat(x).containsEntry("start", 29).containsEntry("end", 32));
    }

    @Test
    @DisplayName("TC-QAR-007(도메인): 재매핑 — 그대로(OK) · 위치 이동(REMAPPED) · 일부 수정(퍼지 REMAPPED) · 문단 삭제(LOST)")
    void anchorRemap() {
        UUID rev2 = UUID.randomUUID();
        Map<String, Object> old = Map.of("type", "TEXT_RANGE", "blockId", B1.toString(), "sectionKey", "ch7.opinion",
                "start", 0, "end", 12, "quote", "1F 기둥 균열 3개소");
        List<Section> same = List.of(new Section("ch7.opinion", "7", 5, "HUMAN", List.of(blk(B1, "1F 기둥 균열 3개소 확인."))));
        assertThat(AnchorResolver.remap(old, rev2, same).status()).isEqualTo(AnchorResolver.Status.OK);
        List<Section> moved = List.of(new Section("ch7.opinion", "7", 5, "HUMAN", List.of(blk(B1, "점검 결과 1F 기둥 균열 3개소."))));
        var r = AnchorResolver.remap(old, rev2, moved);
        assertThat(r.status()).isEqualTo(AnchorResolver.Status.REMAPPED);
        assertThat(r.anchor()).containsEntry("start", 6).containsEntry("revisionId", rev2);
        List<Section> edited = List.of(new Section("ch7.opinion", "7", 5, "HUMAN", List.of(blk(B1, "1F 기둥 균열 2개소 확인."))));
        assertThat(AnchorResolver.remap(old, rev2, edited).status()).isEqualTo(AnchorResolver.Status.REMAPPED);
        List<Section> deleted = new ArrayList<>(List.of(new Section("ch7.opinion", "7", 5, "HUMAN",
                List.of(blk(UUID.randomUUID(), "전혀 다른 의견 문단입니다. 보수 우선순위는 낮다.")))));
        var lost = AnchorResolver.remap(old, rev2, deleted);
        assertThat(lost.status()).isEqualTo(AnchorResolver.Status.LOST);
        assertThat(lost.anchor()).containsEntry("lostQuote", "1F 기둥 균열 3개소");
    }

    @Test
    @DisplayName("NumberExtractor: 단위 정규화 · 범위 · 약 · 층/부재/결함 문맥 · 식별자 숫자 제외")
    void numbers() {
        var ms = NumberExtractor.extract("지하1층 기둥 균열 3곳, 2F 보 박리 약 1.5m², 균열폭 0.2~0.3㎜, D-0001 외 결함 총 7건");
        assertThat(ms).extracting(NumberExtractor.Mention::raw).containsExactly("3곳", "약 1.5m²", "0.2~0.3㎜", "7건");
        assertThat(ms.get(0)).satisfies(m -> {
            assertThat(m.unit()).isEqualTo(NumberExtractor.Unit.COUNT);
            assertThat(m.floor()).isEqualTo("B1");
            assertThat(m.member()).isEqualTo("COLUMN");
            assertThat(m.defectType()).isEqualTo("CRACK");
        });
        assertThat(ms.get(1)).satisfies(m -> {
            assertThat(m.floor()).isEqualTo("2F");
            assertThat(m.member()).isEqualTo("BEAM");
            assertThat(m.defectType()).isEqualTo("SPALL");
            assertThat(m.approx()).isTrue();
            assertThat(m.matches(new BigDecimal("1.55"), BigDecimal.ZERO)).isTrue();
        });
        assertThat(ms.get(2).matches(new BigDecimal("0.25"), BigDecimal.ZERO)).isTrue();
        assertThat(ms.get(3).total()).isTrue();
        // '보수' 의 '보' 는 부재(보)가 아니다
        assertThat(NumberExtractor.extract("보수 대상 3개소").get(0).member()).isNull();
    }
}
