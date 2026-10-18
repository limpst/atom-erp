package com.atomeng.erp.fac.domain.location;

import java.util.Locale;
import java.util.UUID;

/**
 * 부재 코드·ltree 경로 규칙 (FR-FAC-006, 설계 B7.3).
 * 코드: {층}-{유형1자}-{그리드} 예) 3F-C-X2Y3, 3F-G-X2Y3-X3Y3, B2-S-X1Y1-X2Y2. 지하는 B{n}, 0층 없음.
 * ltree 라벨은 영숫자·밑줄만 → b_{uuid hex}.f_3.z_1.m_3f_c_x2y3
 */
public final class MemberCode {

    private MemberCode() {}

    public enum MemberType {
        COL('C'), GIR('G'), BEAM('B'), SLAB('S'), WALL('W'), FDN('F'), STAIR('T'), ETC('E');

        private final char letter;

        MemberType(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }
    }

    public static String floor(int level) {
        if (level == 0) {
            throw new IllegalArgumentException("0층은 없음 (지상 1F, 지하 B1)");
        }
        return level > 0 ? level + "F" : "B" + (-level);
    }

    public static String column(int level, String x, String y) {
        return floor(level) + "-" + MemberType.COL.letter() + "-" + x + y;
    }

    public static String girder(int level, String x1, String y1, String x2, String y2) {
        return floor(level) + "-" + MemberType.GIR.letter() + "-" + x1 + y1 + "-" + x2 + y2;
    }

    public static String slab(int level, String x1, String y1, String x2, String y2) {
        return floor(level) + "-" + MemberType.SLAB.letter() + "-" + x1 + y1 + "-" + x2 + y2;
    }

    public static String member(int level, MemberType type, String grid) {
        return floor(level) + "-" + type.letter() + "-" + grid;
    }

    public static String buildingLabel(UUID buildingId) {
        return "b_" + buildingId.toString().replace("-", "");
    }

    /** 임의 코드 → ltree 라벨 (소문자, 영숫자 외 '_') */
    public static String label(String prefix, String code) {
        return prefix + "_" + code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    public static String floorLabel(int level) {
        return level > 0 ? "f_" + level : "f_b" + (-level);
    }
}
