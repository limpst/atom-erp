package com.atomeng.erp.fcd.domain.ingest;

import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.MappingProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 카드사 명세서 매핑 프로파일 YAML 로더 — Python 온보딩 도구({@code fcd-onboard profile-from-header},
 * {@code atomeng_aip.fraud.ingest.profile_yaml})가 만든 파일을 그대로 읽는다(형식 {@value #FORMAT}).
 * 키는 Python {@code MappingProfile} 필드명(snake_case), {@code profile_id} → {@link MappingProfile#profileCode()}.
 * {@code onboarding} 블록(신뢰도 · 미해결 목록)과 {@code encoding} · {@code delimiter} · {@code form_version} 은 무시한다.
 * SafeConstructor 로만 읽는다(임의 타입 생성 금지).
 */
public final class MappingProfileYaml {

    public static final String FORMAT = "fcd-mapping-profile/v1";
    static final Set<String> CANCEL_MODES = Set.of("NEGATIVE_AMOUNT", "SEPARATE_ROW_FLAG", "STATUS_COLUMN");
    static final Set<String> KINDS = Set.of("APPROVAL", "PURCHASE", "BILLING");
    static final Set<String> FILE_TYPES = Set.of("CSV", "XLSX");

    public static final class ProfileFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProfileFormatException(String m) {
            super(m);
        }
    }

    private MappingProfileYaml() {}

    public static MappingProfile load(InputStream in) throws IOException {
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    public static MappingProfile parse(String yaml) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(doc instanceof Map<?, ?> d)) {
            throw new ProfileFormatException("프로파일 YAML 최상위가 매핑이 아님");
        }
        if (!FORMAT.equals(str(d.get("format")))) {
            throw new ProfileFormatException("지원하지 않는 프로파일 형식: " + d.get("format"));
        }
        for (String k : List.of("profile_id", "card_company", "file_type", "kind", "name", "header_signature", "column_map")) {
            Object v = d.get(k);
            if (v == null || "".equals(v) || (v instanceof List<?> l && l.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty())) {
                throw new ProfileFormatException("필수 키 누락: " + k);
            }
        }
        String cancelMode = d.containsKey("cancel_mode") ? str(d.get("cancel_mode")) : "NEGATIVE_AMOUNT";
        if (!CANCEL_MODES.contains(cancelMode)) {
            throw new ProfileFormatException("cancel_mode 값 오류: " + cancelMode);
        }
        String kind = str(d.get("kind"));
        if (!KINDS.contains(kind)) {
            throw new ProfileFormatException("kind 값 오류: " + kind);
        }
        String fileType = str(d.get("file_type"));
        if (!FILE_TYPES.contains(fileType)) {
            throw new ProfileFormatException("file_type 값 오류: " + fileType);
        }
        if (!(d.get("column_map") instanceof Map<?, ?> cmRaw)) {
            throw new ProfileFormatException("column_map 은 매핑이어야 함");
        }
        Map<String, String> columnMap = new LinkedHashMap<>();
        cmRaw.forEach((k, v) -> columnMap.put(str(k), str(v)));
        List<String> cancelValues = d.containsKey("cancel_values") && d.get("cancel_values") != null
                ? strList(d.get("cancel_values")) : List.of("취소", "승인취소", "매입취소");
        List<String> partial = d.containsKey("partial_cancel_values") && d.get("partial_cancel_values") != null
                ? strList(d.get("partial_cancel_values")) : List.of("부분취소");
        String footer = d.containsKey("footer_stop_pattern") ? str(d.get("footer_stop_pattern")) : "합계|소계|총계";
        boolean active = !d.containsKey("active") || Boolean.TRUE.equals(d.get("active"));
        return new MappingProfile(str(d.get("profile_id")), str(d.get("card_company")), fileType, kind, str(d.get("name")),
                strList(d.get("header_signature")), columnMap, str(d.get("date_format")), cancelMode, cancelValues, partial,
                footer, active);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        if (!(o instanceof List<?> l)) {
            throw new ProfileFormatException("목록이어야 함: " + o);
        }
        List<String> out = new ArrayList<>(l.size());
        l.forEach(x -> out.add(String.valueOf(x)));
        return List.copyOf(out);
    }
}
