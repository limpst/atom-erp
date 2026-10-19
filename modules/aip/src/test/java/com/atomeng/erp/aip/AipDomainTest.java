package com.atomeng.erp.aip;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.aip.domain.AipRules;
import com.atomeng.erp.aip.domain.JobStatus;
import com.atomeng.erp.aip.domain.ModelStage;
import com.atomeng.erp.aip.domain.measure.BinaryGrid;
import com.atomeng.erp.aip.domain.measure.CrackMeasurer;
import com.atomeng.erp.aip.domain.measure.DistanceTransform;
import com.atomeng.erp.aip.domain.measure.Skeletons;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AIP 순수 도메인 — 상태 전이 표(A6.1 · A6.3), 입력 해시, BR-AIP-012 2단계 규칙, 라우팅 버킷, 측정 보조 규칙 */
class AipDomainTest {

    @Test
    @DisplayName("A6.1 Job 전이: 허용 전이만, 재시도 3회 소진 → DEAD, 백오프 2^n")
    void jobTransitions() {
        assertThat(JobStatus.QUEUED.canMove(JobStatus.RUNNING)).isTrue();
        assertThat(JobStatus.QUEUED.canMove(JobStatus.CANCELLED)).isTrue();
        assertThat(JobStatus.RUNNING.canMove(JobStatus.PREEMPTED)).isTrue();
        assertThat(JobStatus.FAILED.canMove(JobStatus.QUEUED)).isTrue();
        assertThat(JobStatus.PREEMPTED.canMove(JobStatus.QUEUED)).isTrue();
        assertThat(JobStatus.SUCCEEDED.canMove(JobStatus.QUEUED)).isFalse();
        assertThat(JobStatus.CANCELLED.terminal()).isTrue();
        assertThat(JobStatus.QUEUED.canMove(JobStatus.SUCCEEDED)).isFalse();
        assertThat(JobStatus.afterFailure(1, 3)).isEqualTo(JobStatus.QUEUED);
        assertThat(JobStatus.afterFailure(3, 3)).isEqualTo(JobStatus.DEAD);
        assertThat(JobStatus.backoffSeconds(1)).isEqualTo(2);
        assertThat(JobStatus.backoffSeconds(3)).isEqualTo(8);
    }

    @Test
    @DisplayName("A6.3 모델 단계: TRAINED→EVALUATED→SHADOW→CANARY→PRODUCTION→ARCHIVED, 건너뛰기 금지, 승인 요건 QA · EXEC")
    void modelStages() {
        assertThat(ModelStage.TRAINED.canMove(ModelStage.SHADOW)).isFalse();
        assertThat(ModelStage.EVALUATED.canMove(ModelStage.SHADOW)).isTrue();
        assertThat(ModelStage.SHADOW.canMove(ModelStage.PRODUCTION)).isFalse();
        assertThat(ModelStage.CANARY.canMove(ModelStage.ROLLED_BACK)).isTrue();
        assertThat(ModelStage.PRODUCTION.canMove(ModelStage.ARCHIVED)).isTrue();
        assertThat(ModelStage.SHADOW.alias()).isEqualTo("shadow");
        assertThat(ModelStage.SHADOW.requiredApprover()).isEqualTo("QA");
        assertThat(ModelStage.PRODUCTION.requiredApprover()).isEqualTo("EXEC");
        assertThat(ModelStage.CANARY.requiredApprover()).isNull();
    }

    @Test
    @DisplayName("입력 정규화 해시: 첨부 순서 · params 키 순서와 무관, 값이 다르면 다름")
    void inputHash() throws Exception {
        ObjectMapper m = new ObjectMapper();
        String a = AipRules.inputHash(List.of("b".repeat(64), "a".repeat(64)), m.readTree("{\"x\":1,\"y\":{\"b\":2,\"a\":1}}"));
        String b = AipRules.inputHash(List.of("a".repeat(64), "b".repeat(64)), m.readTree("{\"y\":{\"a\":1,\"b\":2},\"x\":1}"));
        String c = AipRules.inputHash(List.of("a".repeat(64), "b".repeat(64)), m.readTree("{\"y\":{\"a\":1,\"b\":3},\"x\":1}"));
        assertThat(a).isEqualTo(b).hasSize(64).isNotEqualTo(c);
    }

    @Test
    @DisplayName("BR-AIP-012: 결정 시점은 라벨 큐 후보만(동의 ∧ 블러), 커밋 시점에 검수 승인까지 모두 참이어야 학습 적격")
    void trainingEligibilityTwoStage() {
        assertThat(AipRules.labelQueueCandidate("MODIFIED", true, true, false)).isTrue();
        assertThat(AipRules.labelQueueCandidate("REJECTED", false, true, false)).isFalse();   // 동의 없음
        assertThat(AipRules.labelQueueCandidate("REJECTED", true, false, false)).isFalse();   // 블러 미완료
        assertThat(AipRules.labelQueueCandidate("ACCEPTED", true, true, false)).isFalse();    // 수락은 표본만
        assertThat(AipRules.labelQueueCandidate("ACCEPTED", true, true, true)).isTrue();
        assertThat(AipRules.trainingEligibleAtCommit(true, true, true)).isTrue();
        assertThat(AipRules.trainingEligibleAtCommit(false, true, true)).isFalse();
        assertThat(AipRules.labelPriority(0.9, "REJECTED")).isGreaterThan(AipRules.labelPriority(0.9, "MODIFIED"));
    }

    @Test
    @DisplayName("B7.11 라우팅: murmur3_32 버킷은 결정론적(같은 사진 = 같은 경로), canary 0% = 항상 production")
    void routing() {
        assertThat(AipRules.murmur3("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(0x248bfa47);
        UUID u = UUID.fromString("0192f4c2-0000-7000-8000-000000000001");
        assertThat(AipRules.bucket(u)).isEqualTo(AipRules.bucket(u)).isBetween(0, 99);
        assertThat(AipRules.route(u, 0)).isEqualTo("production");
        assertThat(AipRules.route(u, 100)).isEqualTo("canary");
        long canary = java.util.stream.IntStream.range(0, 2000)
                .filter(i -> "canary".equals(AipRules.route(UUID.nameUUIDFromBytes(new byte[] {(byte) i, (byte) (i >> 8)}), 10)))
                .count();
        assertThat(canary).isBetween(120L, 280L);   // 약 10%
    }

    @Test
    @DisplayName("측정 보조: EDT 정확값, 교차수(끝점 1 · 선 2 · 갈림점 3), 백분위 = numpy linear, 경계 플래그 이름")
    void measureHelpers() {
        BinaryGrid g = new BinaryGrid(9, 9);
        for (int r = 1; r < 8; r++) {
            for (int c = 1; c < 8; c++) {
                g.set(r, c, true);
            }
        }
        assertThat(DistanceTransform.edt(g)[4][4]).isEqualTo(4.0);
        assertThat(DistanceTransform.edt(g)[1][1]).isEqualTo(1.0);
        BinaryGrid t = new BinaryGrid(7, 27);
        for (int c = 1; c <= 25; c++) {
            t.set(3, c, true);
        }
        t.set(2, 13, true);
        t.set(1, 13, true);
        assertThat(Skeletons.crossingNumber(t, 3, 1)).isEqualTo(1);
        assertThat(Skeletons.crossingNumber(t, 3, 13)).isEqualTo(3);
        assertThat(Skeletons.crossingNumber(t, 3, 2)).isEqualTo(2);
        assertThat(CrackMeasurer.percentile(new double[] {1, 2, 3, 4}, 99)).isEqualTo(3.97, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(CrackMeasurer.boundaryFlags(0.3, 0.043, List.of(0.1, 0.2, 0.3))).containsExactly("NEAR_GRADE_BOUNDARY_0_3MM");
        assertThat(Skeletons.pruneSpurs(t, 10).count()).isEqualTo(25);   // 길이 2 곁가지만 제거(본선 양쪽 12px 은 유지)
    }
}
