package com.atomeng.erp.eqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.eqp.domain.EqpRules;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import com.atomeng.erp.eqp.domain.Geo;
import com.atomeng.erp.eqp.domain.QrToken;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** EQP 순수 도메인 단위 테스트 (04-IMPL Step 2 · 5 · 6 · 8 · 11) — 합성 값만 */
class EqpDomainTest {

    /** 설계 A6.1 상태도의 허용 전이(04-IMPL Step 2 표) — 이 외 조합은 모두 거부 */
    static final Map<EquipmentStatus, Set<EquipmentStatus>> DESIGN = Map.of(
            EquipmentStatus.REGISTERED, Set.of(EquipmentStatus.AVAILABLE, EquipmentStatus.IN_CALIBRATION),
            EquipmentStatus.AVAILABLE, Set.of(EquipmentStatus.RESERVED, EquipmentStatus.CHECKED_OUT,
                    EquipmentStatus.CAL_DUE, EquipmentStatus.DISPOSED),
            EquipmentStatus.RESERVED, Set.of(EquipmentStatus.AVAILABLE, EquipmentStatus.CHECKED_OUT, EquipmentStatus.CAL_DUE),
            EquipmentStatus.CHECKED_OUT, Set.of(EquipmentStatus.AVAILABLE, EquipmentStatus.BROKEN, EquipmentStatus.LOST),
            // 편차: CAL_DUE → CHECKED_OUT 은 QA Override 유효 중 반출 경로(설계 A6.1 에 없음 — README 5장)
            EquipmentStatus.CAL_DUE, Set.of(EquipmentStatus.IN_CALIBRATION, EquipmentStatus.CHECKED_OUT),
            EquipmentStatus.IN_CALIBRATION, Set.of(EquipmentStatus.AVAILABLE, EquipmentStatus.BROKEN),
            EquipmentStatus.BROKEN, Set.of(EquipmentStatus.IN_REPAIR),
            EquipmentStatus.IN_REPAIR, Set.of(EquipmentStatus.CAL_DUE, EquipmentStatus.AVAILABLE, EquipmentStatus.DISPOSED),
            EquipmentStatus.LOST, Set.of(EquipmentStatus.AVAILABLE, EquipmentStatus.DISPOSED),
            EquipmentStatus.DISPOSED, Set.of());

    static Stream<Arguments> allPairs() {
        List<Arguments> out = new ArrayList<>();
        for (EquipmentStatus from : EquipmentStatus.values()) {
            for (EquipmentStatus to : EquipmentStatus.values()) {
                out.add(Arguments.of(from, to, DESIGN.get(from).contains(to)));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @MethodSource("allPairs")
    @DisplayName("Step 2: 상태 전이표 100조합 = 설계 A6.1 (+Override 반출 1건), DISPOSED 는 종착")
    void transitionMatrix(EquipmentStatus from, EquipmentStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }

    @Test
    @DisplayName("Step 5 (FR-EQP-002 · B7.4): QR 토큰 eqp1.{code}.{epoch}.{sig16} — 검증 성공, 서명 1글자 변조 · 다른 키(회전) · 다른 코드 거부")
    void qrTokenHmac() {
        byte[] key = "synthetic-qr-secret-32bytes-0001".getBytes(StandardCharsets.UTF_8);
        byte[] rotated = "synthetic-qr-secret-32bytes-0002".getBytes(StandardCharsets.UTF_8);
        String t = QrToken.issue("EQ-NDT-RH-0007", 1_760_000_000L, key);
        assertThat(t).matches("^eqp1\\.EQ-NDT-RH-0007\\.1760000000\\.[A-Za-z0-9_-]{16}$");
        var p = QrToken.parse(t);
        assertThat(p.code()).isEqualTo("EQ-NDT-RH-0007");
        assertThat(QrToken.verify(p, key)).isTrue();
        assertThat(QrToken.verify(p, rotated)).isFalse();
        char last = t.charAt(t.length() - 1);
        String tampered = t.substring(0, t.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThat(QrToken.verify(QrToken.parse(tampered), key)).isFalse();
        String otherCode = t.replace("EQ-NDT-RH-0007", "EQ-NDT-RH-0003");
        assertThat(QrToken.verify(QrToken.parse(otherCode), key)).isFalse();
        String otherEpoch = t.replace(".1760000000.", ".1760000001.");
        assertThat(QrToken.verify(QrToken.parse(otherEpoch), key)).isFalse();
        for (String bad : List.of("", "eqp2.EQ-NDT-RH-0007.1.abcdefghijklmnop", "eqp1.EQ-NDT-RH-0007.1",
                "eqp1.foo.1.abcdefghijklmnop", "eqp1.EQ-NDT-RH-0007.x.abcdefghijklmnop",
                "eqp1.EQ-NDT-RH-0007.1.short")) {
            assertThatThrownBy(() -> QrToken.parse(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("TC-EQP-010(도메인): 승인 폴리곤 안 경로 → 이탈 없음, 한 점이라도 밖 → 이탈 / 경계 위 점은 안")
    void pointInPolygon() {
        List<double[]> ring = List.of(new double[]{127.000, 37.500}, new double[]{127.010, 37.500},
                new double[]{127.010, 37.510}, new double[]{127.000, 37.510});
        List<double[]> inside = List.of(new double[]{127.005, 37.505, 30}, new double[]{127.001, 37.509, 45});
        assertThat(Geo.anyOutside(inside, ring)).isFalse();
        assertThat(Geo.pointInPolygon(127.010, 37.505, ring)).isTrue();   // 경계
        List<double[]> leaves = List.of(new double[]{127.005, 37.505, 30}, new double[]{127.020, 37.505, 40});
        assertThat(Geo.anyOutside(leaves, ring)).isTrue();
        // 오목 다각형(L자) — 오목부 점은 밖
        List<double[]> l = List.of(new double[]{0, 0}, new double[]{2, 0}, new double[]{2, 1}, new double[]{1, 1},
                new double[]{1, 2}, new double[]{0, 2});
        assertThat(Geo.pointInPolygon(1.5, 1.5, l)).isFalse();
        assertThat(Geo.pointInPolygon(0.5, 1.5, l)).isTrue();
    }

    @Test
    @DisplayName("규칙: 사용 일수(양 끝 포함) · 차기일 추정(+주기-1일) · 앤빌 80±2 (TC-EQP-007: 77 거부) · Override 7일(TC-EQP-008: 8일 거부)")
    void rules() {
        assertThat(EqpRules.usageDays(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))).isEqualTo(1);
        assertThat(EqpRules.usageDays(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 17))).isEqualTo(3);
        assertThatThrownBy(() -> EqpRules.usageDays(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 14)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EqpRules.estimateNextDue(LocalDate.of(2026, 3, 10), 12)).isEqualTo(LocalDate.of(2027, 3, 9));
        assertThat(EqpRules.anvilWithin(80.4, 80, 2)).isTrue();
        assertThat(EqpRules.anvilWithin(78, 80, 2)).isTrue();
        assertThat(EqpRules.anvilWithin(77, 80, 2)).isFalse();
        assertThat(EqpRules.anvilWithin(82.01, 80, 2)).isFalse();
        assertThat(EqpRules.overridePeriodOk(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))).isTrue();
        assertThat(EqpRules.overridePeriodOk(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8))).isFalse();
        assertThat(EqpRules.overridePeriodOk(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1))).isFalse();
    }

    @Test
    @DisplayName("FR-EQP-004: D-60/30/7/0 기준 선택 — 남은 일수 이하인 가장 작은 기준(누락 없는 따라잡기)")
    void thresholds() {
        List<Integer> t = List.of(60, 30, 7, 0);
        assertThat(EqpRules.threshold(61, t)).isEmpty();
        assertThat(EqpRules.threshold(60, t)).contains(60);
        assertThat(EqpRules.threshold(45, t)).contains(60);
        assertThat(EqpRules.threshold(30, t)).contains(30);
        assertThat(EqpRules.threshold(8, t)).contains(30);
        assertThat(EqpRules.threshold(7, t)).contains(7);
        assertThat(EqpRules.threshold(1, t)).contains(7);
        assertThat(EqpRules.threshold(0, t)).contains(0);
        assertThat(EqpRules.threshold(-1, t)).isEmpty();
    }
}
