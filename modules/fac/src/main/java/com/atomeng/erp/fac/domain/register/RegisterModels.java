package com.atomeng.erp.fac.domain.register;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 외부 공공 API(도로명주소·건축HUB 건축물대장)를 번역한 내부 값 객체 (02-IMPL Step 3 ACL).
 * 외부 필드명(mainPurpsCdNm 등)은 이 경계 밖으로 나가지 않는다.
 */
public final class RegisterModels {

    private RegisterModels() {}

    /** PNU 19자리 = 법정동코드 10 + 필지구분 1(1 일반, 2 산 ⚠️ 확인 필요) + 본번 4 + 부번 4 */
    public record Pnu(String value) {
        public Pnu {
            if (value == null || !value.matches("^\\d{19}$")) {
                throw new IllegalArgumentException("PNU 19자리 숫자 필요: " + value);
            }
        }

        public static Pnu of(String admCd, boolean mountain, String mainNo, String subNo) {
            return new Pnu(admCd + (mountain ? "2" : "1") + pad4(mainNo) + pad4(subNo));
        }

        private static String pad4(String n) {
            String d = n == null || n.isBlank() ? "0" : n.trim();
            return "0".repeat(Math.max(0, 4 - d.length())) + d;
        }

        public String sigunguCd() { return value.substring(0, 5); }
        public String bjdongCd() { return value.substring(5, 10); }
        /** 건축HUB platGbCd: 0 대지, 1 산 (⚠️ 명세 확인 필요) */
        public String platGbCd() { return value.charAt(10) == '2' ? "1" : "0"; }
        public String bun() { return value.substring(11, 15); }
        public String ji() { return value.substring(15, 19); }
    }

    public record NormalizedAddress(String roadAddress, String jibunAddress, String admCd, Pnu pnu,
                                    String buildingName) {}

    /** 표제부 1건 = building 1건 후보 */
    public record BuildingCandidate(String mgmBldrgstPk, String buildingName, String dongName, String mainPurposeCd,
                                    String mainPurposeNm, String structureCd, String structureNm,
                                    BigDecimal grossFloorArea, Short floorsAbove, Short floorsBelow,
                                    LocalDate approvalDate, String snapshotJson) {}

    /**
     * @param available     false = 장애·쿼터·미설정 → 수기 모드(TC-FAC-002)
     * @param failureReason TIMEOUT, HTTP_5XX, QUOTA_EXCEEDED, NOT_CONFIGURED, API_ERROR …
     */
    public record RegisterLookupResult(boolean available, Pnu pnu, String siteName, List<BuildingCandidate> titles,
                                       int floorOutlineCount, String failureReason) {

        public static RegisterLookupResult unavailable(Pnu pnu, String reason) {
            return new RegisterLookupResult(false, pnu, null, List.of(), 0, reason);
        }

        /** 동 선택: 관리번호 → 동명 → 단일 표제부 */
        public Optional<BuildingCandidate> pick(String mgmBldrgstPk, String dongName) {
            if (mgmBldrgstPk != null) {
                return titles.stream().filter(t -> mgmBldrgstPk.equals(t.mgmBldrgstPk())).findFirst();
            }
            if (dongName != null && !dongName.isBlank()) {
                return titles.stream().filter(t -> dongName.equals(t.dongName())).findFirst();
            }
            return titles.size() == 1 ? Optional.of(titles.get(0)) : Optional.empty();
        }
    }
}
