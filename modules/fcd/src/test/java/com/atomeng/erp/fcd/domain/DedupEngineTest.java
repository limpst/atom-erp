package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.fcd.domain.dedup.DedupEngine;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine.Decision;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine.TxnView;
import com.atomeng.erp.fcd.domain.dedup.TxnDerivation;
import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 채널 간 중복 제거 판정 (설계 B7.2 · BR-FCD-002/003/007/008) — TC-FCD-007 · 008 · 009 · 010 · 011 · 012 (합성) */
class DedupEngineTest {

    static final UUID CARD = UUID.randomUUID();

    /** 메모리 원장(판정 조회만) */
    static final class Mem implements DedupEngine.LedgerLookup {
        final List<TxnView> txns = new ArrayList<>();
        final Set<String> hashes = new HashSet<>();
        final Set<String> channelKinds = new HashSet<>();

        @Override
        public boolean rawHashExists(String channel, String kind, String rawHash) {
            return hashes.contains(channel + kind + rawHash);
        }

        @Override
        public List<TxnView> byApproval(UUID cardId, String approvalNo) {
            return txns.stream().filter(t -> t.cardId().equals(cardId) && approvalNo.equals(t.approvalNo())).toList();
        }

        @Override
        public List<TxnView> byCardDay(UUID cardId, LocalDate day) {
            return txns.stream().filter(t -> t.cardId().equals(cardId) && t.approvedAt().toLocalDate().equals(day)).toList();
        }

        @Override
        public boolean hasSameChannelKind(UUID txnId, String channel, String kind) {
            return channelKinds.contains(txnId + channel + kind);
        }

        UUID add(CanonicalRow r) {
            UUID id = UUID.randomUUID();
            txns.add(new TxnView(id, r.cardId(), r.approvalNo(), r.approvedAt(), r.precision(), r.amountKrw(),
                    r.kind().equals("APPROVAL") ? r.amountKrw() : null, r.currency(), r.merchantName(), r.bizRegNo(),
                    new ArrayList<>()));
            hashes.add(r.channel() + r.kind() + r.rawHash());
            channelKinds.add(id + r.channel() + r.kind());
            return id;
        }
    }

    static CanonicalRow row(String channel, String kind, String appr, LocalDateTime at, String prec, long amt, String merchant,
                            String biz, String hash) {
        return new CanonicalRow(channel, kind, CARD, appr, at, prec, amt, merchant, biz, null, "KRW", null, null, null, null,
                null, null, null, 1, Map.of(), hash);
    }

    @Test
    @DisplayName("TC-FCD-009: API(승인번호) → Excel(같은 승인번호 · 같은 분 · 금액) = NATURAL_KEY 병합 / 같은 원문 재수신 = DUPLICATE")
    void naturalKey() {
        Mem m = new Mem();
        CanonicalRow api = row("API", "APPROVAL", "00123456", LocalDateTime.of(2026, 9, 1, 12, 5, 31), "SECOND", 88_000,
                "(예시)한강설렁탕 하남점", "999-81-00001", "h1");
        UUID t = m.add(api);
        Decision d = DedupEngine.decide(row("EXCEL", "PURCHASE", "00123456", LocalDateTime.of(2026, 9, 1, 12, 5), "MINUTE",
                88_000, "한강설렁탕", null, "h2"), m);
        assertThat(d.bucket()).isEqualTo("MERGED");
        assertThat(d.method()).isEqualTo("NATURAL_KEY");
        assertThat(d.txnId()).isEqualTo(t);
        assertThat(DedupEngine.decide(api, m).bucket()).isEqualTo("DUPLICATE");
        // 날짜만 있는 매입 명세(승인번호 같음, ±1일) → APPROVAL_NO 2차 키(설계 보완점 2)
        Decision d2 = DedupEngine.decide(row("EXCEL", "PURCHASE", "00123456", LocalDateTime.of(2026, 9, 2, 0, 0), "DATE",
                88_000, "한강설렁탕", null, "h3"), m);
        assertThat(d2.method()).isEqualTo("APPROVAL_NO");
    }

    @Test
    @DisplayName("TC-FCD-010 · 012: API(초) → CSV(승인번호 없음 · 분 · 가맹점명 잘림) 퍼지 ≥ 0.92 병합 / 날짜만 · 가맹점 유사 0.8 → 확인 큐 0.75–0.92")
    void fuzzy() {
        Mem m = new Mem();
        UUID t = m.add(row("API", "APPROVAL", "55810918", LocalDateTime.of(2026, 9, 1, 8, 46, 12), "SECOND", 3_800,
                "(예시)커피정원 서울강동점", "999-88-23036", "a"));
        Decision d = DedupEngine.decide(row("CSV", "APPROVAL", null, LocalDateTime.of(2026, 9, 1, 8, 46), "MINUTE", 3_800,
                "커피정원서울강", null, "b"), m);
        assertThat(d.bucket()).isEqualTo("MERGED");
        assertThat(d.method()).isEqualTo("FUZZY");
        assertThat(d.score()).isGreaterThanOrEqualTo(0.92);
        assertThat(d.txnId()).isEqualTo(t);
        Decision q = DedupEngine.decide(row("EXCEL", "PURCHASE", null, LocalDateTime.of(2026, 9, 1, 0, 0), "DATE", 3_800,
                "커피정원본점카페", null, "c"), m);
        assertThat(q.bucket()).isEqualTo("REVIEW");
        assertThat(q.score()).isBetween(0.75, 0.92);
    }

    @Test
    @DisplayName("TC-FCD-011: 같은 파일 안 동일 시각 · 금액 · 가맹점 2행(원문 해시 다름) → 병합하지 않고 별개 거래")
    void sameFileTwinRows() {
        Mem m = new Mem();
        m.add(row("CSV", "APPROVAL", null, LocalDateTime.of(2026, 9, 1, 10, 0), "MINUTE", 4_500, "(예시)바른커피", null, "x#1"));
        Decision d = DedupEngine.decide(row("CSV", "APPROVAL", null, LocalDateTime.of(2026, 9, 1, 10, 0), "MINUTE", 4_500,
                "(예시)바른커피", null, "x#2"), m);
        assertThat(d.bucket()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("TC-FCD-007 · 008: 취소(원거래 매칭 · 같은 취소 재수신 CANCEL_DUP · 원거래 없음 ORPHAN) / 해외 매입 원화 우선 · 전액·부분 취소 상태")
    void cancelsAndDerivation() {
        Mem m = new Mem();
        CanonicalRow appr = new CanonicalRow("API", "APPROVAL", CARD, "77770001", LocalDateTime.of(2026, 9, 3, 19, 0),
                "SECOND", 165_000, "(예시)NY GRILL", null, null, "USD", 120.0, null, null, null, null, null, "US", 1, Map.of(),
                "u1");
        UUID t = m.add(appr);
        CanonicalRow purch = new CanonicalRow("EXCEL", "PURCHASE", CARD, "77770001", LocalDateTime.of(2026, 9, 3, 0, 0),
                "DATE", 166_920, "(예시)NY GRILL", null, null, "USD", 120.0, 1391.0, null, null, null, null, null, 2, Map.of(),
                "u2");
        assertThat(DedupEngine.decide(purch, m).bucket()).isEqualTo("MERGED");   // 해외 ±2% 허용
        CanonicalRow cancel = new CanonicalRow("CSV", "CANCEL", CARD, "77770001", LocalDateTime.of(2026, 9, 5, 9, 0),
                "MINUTE", 0, "(예시)NY GRILL", null, null, "USD", null, null, 40_000L, true,
                LocalDateTime.of(2026, 9, 5, 9, 0), null, null, 3, Map.of(), "u3");
        Decision c = DedupEngine.decide(cancel, m);
        assertThat(c.bucket()).isEqualTo("CANCEL");
        assertThat(c.txnId()).isEqualTo(t);
        m.txns.set(0, new TxnView(t, CARD, "77770001", appr.approvedAt(), "SECOND", 166_920, 165_000L, "USD",
                "(예시)NY GRILL", null, List.of(DedupEngine.cancelKey(cancel))));
        assertThat(DedupEngine.decide(cancel.withRawHash("u4"), m).bucket()).isEqualTo("CANCEL_DUP");
        CanonicalRow orphan = new CanonicalRow("CSV", "CANCEL", CARD, "99990000", LocalDateTime.of(2026, 9, 6, 9, 0),
                "MINUTE", 0, "(예시)다른가게", null, null, "KRW", null, null, 5_000L, null, null, null, null, 4, Map.of(), "u5");
        assertThat(DedupEngine.decide(orphan, m).bucket()).isEqualTo("ORPHAN_CANCEL");

        var cur = new TxnDerivation.Current("77770001", appr.approvedAt(), "SECOND", null, null, null, "USD");
        var d = TxnDerivation.derive(cur, List.of(appr, purch, cancel), "x");
        assertThat(d.amountKrw()).isEqualTo(166_920);          // BR-FCD-008 매입 원화 우선
        assertThat(d.approvalAmountKrw()).isEqualTo(165_000L); // 차이는 대사 정보로만
        assertThat(d.approvedAt()).isEqualTo(appr.approvedAt()); // 시각은 정밀도 높은 원천
        assertThat(d.status()).isEqualTo("PARTIALLY_CANCELLED");
        assertThat(d.cancelledAmountKrw()).isEqualTo(40_000);
        CanonicalRow full = new CanonicalRow("CSV", "CANCEL", CARD, "77770001", LocalDateTime.of(2026, 9, 7, 9, 0), "MINUTE",
                0, "(예시)NY GRILL", null, null, "USD", null, null, 166_920L, false, LocalDateTime.of(2026, 9, 7, 9, 0), null,
                null, 5, Map.of(), "u6");
        assertThat(TxnDerivation.derive(cur, List.of(appr, purch, full), "x").status()).isEqualTo("CANCELLED");
    }
}
