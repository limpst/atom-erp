package com.atomeng.erp.dem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.dem.domain.AdjacentMatcher;
import com.atomeng.erp.dem.domain.AdjacentMatcher.Defect;
import com.atomeng.erp.dem.domain.AdjacentMatcher.Kind;
import com.atomeng.erp.dem.domain.AdjacentMatcher.Thresholds;
import com.atomeng.erp.dem.domain.DemStates.CommentStatus;
import com.atomeng.erp.dem.domain.DemStates.ProjectStatus;
import com.atomeng.erp.dem.domain.DemStates.RevisionStatus;
import com.atomeng.erp.dem.domain.RevCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DEM 순수 도메인 — 상태 전이 표(A6.1 · A6.2 · A6.3), 리비전 채번, 인접건축물 매칭(B7.2, TC-DEM-011). 합성 데이터만. */
class DemDomainTest {

    static final Thresholds T = new Thresholds(new BigDecimal("0.5"), new BigDecimal("0.1"), new BigDecimal("20"));

    @Test
    @DisplayName("A6.1 프로젝트 전이: 8×8 = 64조합 중 허용 10건, 나머지 54건 거부(DEM-409-004)")
    void projectTransitions() {
        int allowed = 0;
        for (ProjectStatus a : ProjectStatus.values()) {
            for (ProjectStatus b : ProjectStatus.values()) {
                if (a.canMoveTo(b)) {
                    allowed++;
                } else {
                    assertThatThrownBy(() -> a.assertCanMoveTo(b)).isInstanceOf(DomainException.class)
                            .extracting("code").isEqualTo("DEM-409-004");
                }
            }
        }
        assertThat(allowed).isEqualTo(10);
        assertThat(ProjectStatus.PERMIT_PENDING.canMoveTo(ProjectStatus.IN_PROGRESS)).isTrue();
        assertThat(ProjectStatus.PLANNING.canMoveTo(ProjectStatus.IN_PROGRESS)).isFalse();
        assertThat(ProjectStatus.CLOSED.canMoveTo(ProjectStatus.PLANNING)).isFalse();
    }

    @Test
    @DisplayName("A6.2 리비전 · A6.3 의견 전이, BR-DEM-009 미결 = OPEN · RESPONDED · REJECTED")
    void revisionAndCommentTransitions() {
        assertThat(RevisionStatus.APPROVED.canMoveTo(RevisionStatus.ISSUED)).isTrue();
        assertThat(RevisionStatus.WIP.canMoveTo(RevisionStatus.ISSUED)).isFalse();
        assertThat(RevisionStatus.ISSUED.canMoveTo(RevisionStatus.SUPERSEDED)).isTrue();
        assertThat(RevisionStatus.SUPERSEDED.canMoveTo(RevisionStatus.ISSUED)).isFalse();
        assertThat(RevisionStatus.ISSUED.published()).isTrue();
        assertThat(RevisionStatus.APPROVED.published()).isFalse();

        assertThat(CommentStatus.REJECTED.unresolved()).isTrue();
        assertThat(CommentStatus.RESPONDED.unresolved()).isTrue();
        assertThat(CommentStatus.OPEN.unresolved()).isTrue();
        assertThat(CommentStatus.ACCEPTED.unresolved()).isFalse();
        assertThat(CommentStatus.CLOSED.unresolved()).isFalse();
        assertThat(CommentStatus.REJECTED.canMoveTo(CommentStatus.RESPONDED)).isTrue();
        assertThat(CommentStatus.REJECTED.canMoveTo(CommentStatus.CLOSED)).isFalse();
        assertThat(CommentStatus.OPEN.canMoveTo(CommentStatus.CLOSED)).isTrue();   // 철회
        assertThat(CommentStatus.OPEN.canMoveTo(CommentStatus.ACCEPTED)).isFalse();
    }

    @Test
    @DisplayName("10-IMPL Step 11 RevCode: Z → AA, AZ → BA, ZZ → AAA, 발행 0 → 1, 정렬 A < Z < AA")
    void revCodes() {
        assertThat(RevCode.nextInternal(null)).isEqualTo("A");
        assertThat(RevCode.nextInternal("A")).isEqualTo("B");
        assertThat(RevCode.nextInternal("Z")).isEqualTo("AA");
        assertThat(RevCode.nextInternal("AZ")).isEqualTo("BA");
        assertThat(RevCode.nextInternal("ZZ")).isEqualTo("AAA");
        assertThat(RevCode.nextIssued(Optional.empty())).isEqualTo("0");
        assertThat(RevCode.nextIssued(Optional.of("9"))).isEqualTo("10");
        assertThat(List.of("AA", "B", "Z", "A").stream().sorted(RevCode::compareInternal).toList())
                .containsExactly("A", "B", "Z", "AA");
    }

    static Defect d(String no, String floor, String member, String type, String x, String y, String width, String len) {
        return new Defect(UUID.nameUUIDFromBytes(no.getBytes(java.nio.charset.StandardCharsets.UTF_8)), no, "ADJ-1",
                floor, member, type, new BigDecimal(x), new BigDecimal(y), width == null ? null : new BigDecimal(width),
                len == null ? null : new BigDecimal(len));
    }

    /** TC-DEM-011 픽스처: 사전 10건 · 사후 12건(진전 2 · 신규 2 · 동일 8) */
    static List<Defect> pre() {
        return List.of(
                d("PRE-01", "1F", "W1", "CRACK", "1.00", "1.00", "0.20", "500"),
                d("PRE-02", "1F", "W1", "CRACK", "3.00", "1.00", "0.30", "800"),
                d("PRE-03", "1F", "W1", "LEAK", "5.00", "2.00", null, null),
                d("PRE-04", "1F", "W2", "CRACK", "0.50", "0.50", "0.10", "300"),
                d("PRE-05", "1F", "W2", "CRACK", "0.80", "0.50", "0.15", "300"),   // 근접 결함 2건 — 헝가리안 1:1
                d("PRE-06", "2F", "C1", "SPALL", "2.00", "2.00", null, null),
                d("PRE-07", "2F", "C1", "CRACK", "2.20", "2.00", "0.25", "400"),
                d("PRE-08", "2F", "S1", "CRACK", "4.00", "4.00", "0.40", "1000"),
                d("PRE-09", "3F", "W5", "CRACK", "1.00", "6.00", "0.20", "600"),
                d("PRE-10", "3F", "W5", "TILT", "9.00", "9.00", null, null));
    }

    static List<Defect> post() {
        return List.of(
                d("POST-01", "1F", "W1", "CRACK", "1.10", "1.05", "0.25", "520"),     // 동일(폭 +0.05, 길이 +4%)
                d("POST-02", "1F", "W1", "CRACK", "3.05", "1.00", "0.45", "820"),     // 진전(폭 +0.15)
                d("POST-03", "1F", "W1", "LEAK", "5.10", "2.10", null, null),         // 동일
                d("POST-04", "1F", "W2", "CRACK", "0.75", "0.52", "0.17", "310"),     // PRE-05 와 매칭(동일)
                d("POST-05", "1F", "W2", "CRACK", "0.45", "0.55", "0.12", "310"),     // PRE-04 와 매칭(동일)
                d("POST-06", "2F", "C1", "SPALL", "2.05", "1.95", null, null),        // 동일
                d("POST-07", "2F", "C1", "CRACK", "2.25", "2.05", "0.30", "500"),     // 진전(길이 +25%)
                d("POST-08", "2F", "S1", "CRACK", "4.10", "4.10", "0.45", "1100"),    // 동일(폭 +0.05, 길이 +10%)
                d("POST-09", "3F", "W5", "CRACK", "1.20", "6.10", "0.20", "600"),     // 동일
                d("POST-10", "3F", "W5", "TILT", "9.00", "9.30", null, null),         // 동일
                d("POST-11", "1F", "W1", "CRACK", "7.00", "1.00", "0.10", "200"),     // 신규(거리 초과)
                d("POST-12", "2F", "C1", "CRACK", "6.00", "2.00", "0.20", "300"));    // 신규(거리 초과)
    }

    @Test
    @DisplayName("TC-DEM-011: 사전 10 · 사후 12 → 진전 2 · 신규 2 · 동일 8 정확 분류, 보수/미확인 0")
    void tcDem011_adjacentComparison() {
        var r = AdjacentMatcher.compare(pre(), post(), T);
        assertThat(r.summary()).containsEntry(Kind.PROGRESSED, 2).containsEntry(Kind.NEW, 2).containsEntry(Kind.SAME, 8)
                .containsEntry(Kind.REPAIRED_OR_UNCONFIRMED, 0);
        assertThat(r.rows().stream().filter(x -> x.kind() == Kind.PROGRESSED).map(x -> x.preNo() + ">" + x.postNo()))
                .containsExactlyInAnyOrder("PRE-02>POST-02", "PRE-07>POST-07");
        assertThat(r.rows().stream().filter(x -> x.kind() == Kind.NEW).map(x -> x.postNo()))
                .containsExactlyInAnyOrder("POST-11", "POST-12");
        // 근접 결함 2쌍은 총 거리 최소인 1:1 (PRE-04↔POST-05, PRE-05↔POST-04)
        assertThat(r.rows().stream().filter(x -> "PRE-04".equals(x.preNo())).findFirst().orElseThrow().postNo())
                .isEqualTo("POST-05");
        assertThat(r.rows().stream().filter(x -> "PRE-02".equals(x.preNo())).findFirst().orElseThrow().widthDeltaMm())
                .isEqualByComparingTo("0.15");
    }

    @Test
    @DisplayName("B7.2: 결과는 입력 순서와 무관(100회 섞기) · 유형 다르면 후보 아님 · 사후 결함 없으면 보수/미확인")
    void matcherDeterministicAndEdgeCases() {
        var expected = AdjacentMatcher.compare(pre(), post(), T);
        Random rnd = new Random(10);
        for (int i = 0; i < 100; i++) {
            List<Defect> a = new ArrayList<>(pre());
            List<Defect> b = new ArrayList<>(post());
            Collections.shuffle(a, rnd);
            Collections.shuffle(b, rnd);
            assertThat(AdjacentMatcher.compare(a, b, T)).isEqualTo(expected);
        }
        var typeMismatch = AdjacentMatcher.compare(List.of(d("P", "1F", "W", "CRACK", "0", "0", "0.1", "100")),
                List.of(d("Q", "1F", "W", "LEAK", "0", "0", null, null)), T);
        assertThat(typeMismatch.summary()).containsEntry(Kind.NEW, 1).containsEntry(Kind.REPAIRED_OR_UNCONFIRMED, 1);
        var gone = AdjacentMatcher.compare(pre(), List.of(), T);
        assertThat(gone.summary()).containsEntry(Kind.REPAIRED_OR_UNCONFIRMED, 10);
        // 경계: 거리 정확히 0.5m 는 후보, 폭 증가 정확히 0.10mm 는 진전
        var edge = AdjacentMatcher.compare(List.of(d("P", "1F", "W", "CRACK", "0", "0", "0.20", "100")),
                List.of(d("Q", "1F", "W", "CRACK", "0.3", "0.4", "0.30", "100")), T);
        assertThat(edge.summary()).containsEntry(Kind.PROGRESSED, 1);
    }
}
