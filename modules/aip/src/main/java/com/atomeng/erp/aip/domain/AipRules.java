package com.atomeng.erp.aip.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** AIP 순수 규칙 모음 — 입력 해시 · 학습 적격 2단계(BR-AIP-012) · 라우팅 버킷(B7.11) · 라벨 우선순위(Step 8). */
public final class AipRules {

    /** 키 정렬 · 공백 없는 정규 JSON(입력 해시 · 출력 해시용) */
    public static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).build();

    private AipRules() {}

    public static String sha256Hex(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 정규 JSON(객체 키 재귀 정렬) 문자열 */
    public static String canonical(JsonNode node) {
        try {
            Object tree = CANONICAL.treeToValue(node == null ? CANONICAL.nullNode() : node, Object.class);
            return CANONICAL.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalArgumentException("정규 JSON 변환 실패", e);
        }
    }

    /**
     * 입력 정규화 해시 (11-IMPL Step 1): sha256(정렬된 첨부 sha256 목록 줄바꿈 연결 + "|" + 정규 params).
     * 같은 사진 묶음 · 같은 파라미터면 순서가 달라도 같은 값.
     */
    public static String inputHash(List<String> attachmentSha256s, JsonNode params) {
        List<String> s = new ArrayList<>(attachmentSha256s == null ? List.of() : attachmentSha256s);
        Collections.sort(s);
        return sha256Hex(String.join("\n", s) + "|" + canonical(params));
    }

    /**
     * BR-AIP-012 ① 결정 시점: training_eligible 은 항상 false. (b) 고객 동의 ∧ (c) 블러 완료면 라벨링 큐 후보로만 등록.
     * 수락(ACCEPTED)은 "약한 양성" 표본 — acceptSampled 가 참일 때만 후보.
     */
    public static boolean labelQueueCandidate(String decision, boolean consent, boolean blurDone, boolean acceptSampled) {
        boolean base = !"ACCEPTED".equals(decision) || acceptSampled;
        return base && consent && blurDone;
    }

    /** BR-AIP-012 ② 데이터셋 커밋 시점: (a) 검수 승인 ∧ (b) 동의 ∧ (c) 블러 — 모두 참인 피드백만 true 로 바꾼다 */
    public static boolean trainingEligibleAtCommit(boolean adjudicationApproved, boolean consent, boolean blurDone) {
        return adjudicationApproved && consent && blurDone;
    }

    /** 라벨링 우선순위: 거절 · 저신뢰 우선 (11-IMPL Step 8) */
    public static double labelPriority(double confidence, String decision) {
        double base = 1.0 - confidence;
        return base + switch (decision) {
            case "REJECTED" -> 1.0;
            case "MODIFIED" -> 0.5;
            default -> 0.0;
        };
    }

    /** 수락 약한 양성 결정론적 표본(제안 id 해시 기준 rate 비율) */
    public static boolean acceptSampled(UUID suggestionId, double rate) {
        return bucket(suggestionId) < Math.round(rate * 100);
    }

    /**
     * 라우팅 버킷 0–99 (설계 B7.11: 같은 사진은 항상 같은 경로) — murmur3_32(seed 0) of UUID 16바이트(big-endian).
     */
    public static int bucket(UUID subjectId) {
        ByteBuffer b = ByteBuffer.allocate(16);
        b.putLong(subjectId.getMostSignificantBits()).putLong(subjectId.getLeastSignificantBits());
        return Math.floorMod(murmur3x86_32(b.array(), 0), 100);
    }

    /** MurmurHash3 x86 32bit, seed 0 (Guava murmur3_32_fixed 와 같은 값) */
    public static int murmur3(byte[] data) {
        return murmur3x86_32(data, 0);
    }

    @SuppressWarnings("fallthrough")
    static int murmur3x86_32(byte[] data, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int len = data.length;
        int nblocks = len / 4;
        for (int i = 0; i < nblocks; i++) {
            int k1 = (data[i * 4] & 0xff) | ((data[i * 4 + 1] & 0xff) << 8) | ((data[i * 4 + 2] & 0xff) << 16)
                    | ((data[i * 4 + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }
        int k1 = 0;
        int tail = nblocks * 4;
        switch (len & 3) {
            case 3:
                k1 ^= (data[tail + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tail + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= data[tail] & 0xff;
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
                break;
            default:
                break;
        }
        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    /** canary 비율 안이면 canary, 아니면 production (설계 B7.11) */
    public static String route(UUID subjectId, Integer canaryPct) {
        return canaryPct != null && bucket(subjectId) < canaryPct ? "canary" : "production";
    }
}
