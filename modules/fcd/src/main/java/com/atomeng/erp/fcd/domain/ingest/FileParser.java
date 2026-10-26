package com.atomeng.erp.fcd.domain.ingest;

import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.HeaderMatch;
import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.MappingProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CSV · Excel 파일 수집(설계 B7.1, 12-IMPL Step 2) — 감지 → 매핑 → 행 검증 → 표준 레코드.
 * Python {@code fraud/ingest/fileimport.py parse_file} 이식. 행 오류는 행번호 · 컬럼 · 코드 · 원문(PAN 마스킹) · 메시지로 모은다.
 */
public final class FileParser {

    private FileParser() {}

    /** 카드 식별(마스킹 번호 앞6 · 뒤4 · 별칭 · 드물게 전체 PAN → 즉시 토큰) */
    public interface CardResolver {
        UUID resolve(String ref, String cardCompany, String alias) throws CardLookupException;
    }

    public static final class CardLookupException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public CardLookupException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record RowError(int rowNo, String column, String code, String rawValue, String message) {}

    public record ParseResult(String fileSha256, String fileKind, String channel, String encoding, String delimiter,
                              Integer headerRow, int headerRows, MappingProfile profile, double profileScore,
                              boolean needsMapping, List<CanonicalRow> rows, List<RowError> errors, List<String> warnings,
                              List<String> detectedHeaders, int rowsTotal) {
        public int rowsError() {
            return (int) errors.stream().map(RowError::rowNo).distinct().count();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sort_keys JSON 의 SHA-256 (채널 내 동일 행 재수신 멱등키) */
    public static String rawHash(Map<String, Object> raw) {
        try {
            return sha256(JSON.writeValueAsString(new TreeMap<>(raw)).getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object cell(List<Object> row, Integer i) {
        if (i == null || i >= row.size()) {
            return null;
        }
        Object v = row.get(i);
        if (v instanceof String s) {
            s = s.strip();
            return s.isEmpty() ? null : s;
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static ParseResult parse(byte[] data, CardResolver cards, List<MappingProfile> profiles,
                                    MappingProfile forced) {
        String sha = sha256(data);
        RowSource.Result src = RowSource.read(data);
        String channel = src.kind().equals("CSV") ? "CSV" : "EXCEL";
        List<String> warnings = new ArrayList<>(src.warnings());
        HeaderMatch hm;
        try {
            List<MappingProfile> known = new ArrayList<>(profiles == null ? List.of() : profiles);
            if (forced != null) {
                known.add(forced);
            }
            hm = new HeaderDetector(known).detect(src.rows().subList(0, Math.min(30, src.rows().size())));
        } catch (HeaderDetector.HeaderNotFound e) {
            return new ParseResult(sha, src.kind(), channel, src.encoding(), src.delimiter(), null, 1, null, 0, true,
                    List.of(), List.of(), warnings, List.of(), 0);
        }
        MappingProfile profile = forced;
        double score = 1.0;
        if (profile == null) {
            HeaderDetector.ProfilePick pick = HeaderDetector.pickProfile(hm, profiles, 0.8);
            score = pick.score();
            profile = pick.profile().orElse(null);
        }
        if (profile == null) {
            return new ParseResult(sha, src.kind(), channel, src.encoding(), src.delimiter(), hm.headerRow(), hm.nRows(),
                    null, score, true, List.of(), List.of(), warnings, hm.headers(), 0);
        }
        Map<String, Integer> col = profile.columnIndex(hm);
        for (String f : List.of("approved_date", "amount", "merchant_name", "card_ref")) {
            if (!col.containsKey(f)) {
                warnings.add("필수 필드 매핑 누락: " + f);
                return new ParseResult(sha, src.kind(), channel, src.encoding(), src.delimiter(), hm.headerRow(),
                        hm.nRows(), profile, score, true, List.of(), List.of(), warnings, hm.headers(), 0);
            }
        }
        Pattern footer = profile.footerStopPattern() == null || profile.footerStopPattern().isEmpty() ? null
                : Pattern.compile(profile.footerStopPattern());
        List<String> headers = hm.headers();
        Map<String, Integer> occ = new HashMap<>();
        List<CanonicalRow> out = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int rowsTotal = 0;
        for (int idx = hm.dataStart(); idx < src.rows().size(); idx++) {
            List<Object> row = src.rows().get(idx);
            int rowNo = idx + 1;
            if (row.stream().allMatch(c -> c == null || "".equals(c))) {
                continue;
            }
            String first = String.valueOf(Optional.ofNullable(cell(row, 0)).orElse(""));
            if (footer != null && footer.matcher(first.strip()).matches()) {
                break;
            }
            rowsTotal++;
            Map<String, Object> raw = new HashMap<>();
            for (int i = 0; i < row.size() && i < headers.size(); i++) {
                Object v = row.get(i);
                if (v == null || "".equals(v)) {
                    continue;
                }
                String key = HeaderDetector.normHeader(headers.get(i));
                if (key.isEmpty()) {
                    key = "col" + i;
                }
                raw.put(key, v instanceof LocalDateTime dt ? dt.toString() : PanTools.maskPans(String.valueOf(v)));
            }
            List<RowError> errs = new ArrayList<>();
            // 카드
            Object cardRaw = cell(row, col.get("card_ref"));
            UUID cardId = null;
            try {
                cardId = cards.resolve(cardRaw == null ? "" : String.valueOf(cardRaw), profile.cardCompany(),
                        str(cell(row, col.get("card_alias"))));
            } catch (CardLookupException e) {
                errs.add(err(rowNo, "card_ref", e.code(), cardRaw, e.getMessage()));
            }
            // 일시
            Object dRaw = cell(row, col.get("approved_date"));
            LocalDateTime when = null;
            String prec = "DATE";
            try {
                if (col.containsKey("approved_time")) {
                    LocalDate d = Values.parseDate(dRaw, profile.dateFormat());
                    Object[] t = Values.parseTime(cell(row, col.get("approved_time")));
                    prec = (String) t[1];
                    when = d.atTime(t[0] == null ? LocalTime.MIDNIGHT : (LocalTime) t[0]);
                } else {
                    Values.Timed t = Values.parseDatetime(dRaw, profile.dateFormat());
                    when = t.at();
                    prec = t.precision();
                }
            } catch (Values.ValueParseException e) {
                errs.add(err(rowNo, "approved_date", e.code(), dRaw, "날짜/시간 형식 오류"));
            }
            // 금액
            Object aRaw = cell(row, col.get("amount"));
            BigDecimal amount = null;
            try {
                amount = Values.parseAmount(aRaw);
            } catch (Values.ValueParseException e) {
                errs.add(err(rowNo, "amount", e.code(), aRaw, "금액 형식 오류"));
            }
            Object merchant = cell(row, col.get("merchant_name"));
            if (merchant == null) {
                errs.add(err(rowNo, "merchant_name", "E-REQUIRED", null, "가맹점명 누락"));
            }
            if (!errs.isEmpty()) {
                errors.addAll(errs);
                continue;
            }
            String bizno = str(cell(row, col.get("biz_reg_no")));
            if (bizno != null && !MerchantText.validBizNo(bizno)) {
                warnings.add("W-BIZNO row " + rowNo);
            }
            String status = Optional.ofNullable(str(cell(row, col.get("status")))).orElse("").strip();
            String note = Optional.ofNullable(str(cell(row, col.get("note")))).orElse("");
            String kind = profile.kind();
            Long cancelAmount = null;
            Boolean partial = null;
            LocalDateTime cancelAt = null;
            long amt = amount.longValue();   // Python int(Decimal) — 0 쪽 절사
            if ("NEGATIVE_AMOUNT".equals(profile.cancelMode()) && amt < 0) {
                kind = "CANCEL";
                cancelAmount = -amt;
                cancelAt = when;
                partial = note.contains("부분") ? Boolean.TRUE : null;
            } else if ("STATUS_COLUMN".equals(profile.cancelMode()) && (profile.cancelValues().contains(status)
                    || profile.partialCancelValues().contains(status))) {
                kind = "CANCEL";
                cancelAmount = Math.abs(amt);
                cancelAt = when;
                partial = profile.partialCancelValues().contains(status);
            } else if ("SEPARATE_ROW_FLAG".equals(profile.cancelMode()) && profile.cancelValues().contains(status)) {
                kind = "CANCEL";
                cancelAmount = Math.abs(amt);
                Object pon = cell(row, col.get("purchased_on"));
                try {
                    cancelAt = pon != null ? Values.parseDate(pon, null).atStartOfDay() : when;
                } catch (Values.ValueParseException e) {
                    cancelAt = when;
                }
            } else if (amt < 0) {
                errors.add(new RowError(rowNo, "amount", "E-AMOUNT-NEGATIVE", PanTools.maskPans(String.valueOf(aRaw)),
                        "취소 표현 방식과 맞지 않는 음수 금액"));
                continue;
            }
            String cur = Optional.ofNullable(str(cell(row, col.get("currency")))).orElse("KRW").toUpperCase(Locale.ROOT);
            Object fxForeign = cell(row, col.get("amount_foreign"));
            Double foreign = null;
            if (fxForeign != null) {
                try {
                    foreign = Values.parseAmount(fxForeign).doubleValue();
                } catch (Values.ValueParseException e) {
                    foreign = null;
                }
            }
            Object fxRaw = cell(row, col.get("fx_rate"));
            Double fxRate = null;
            if (fxRaw != null) {
                try {
                    fxRate = Double.parseDouble(String.valueOf(fxRaw).replace(",", ""));
                } catch (NumberFormatException e) {
                    fxRate = null;
                }
            }
            String appr = str(cell(row, col.get("approval_no")));
            appr = appr == null ? null : appr.strip();
            String cat = str(cell(row, col.get("category")));
            CanonicalRow cr = new CanonicalRow(channel, kind, cardId, appr, when, prec,
                    !"CANCEL".equals(kind) ? Math.abs(amt) : 0, PanTools.maskPans(String.valueOf(merchant)), bizno,
                    PanTools.maskPans(cat),
                    cur.isEmpty() ? "KRW" : cur, foreign, fxRate, cancelAmount, partial, cancelAt, null, null, rowNo,
                    raw, "");
            Map<String, Object> baseMap = new HashMap<>(raw);
            baseMap.put("file_kind", profile.kind());
            String base = rawHash(baseMap);
            int k = occ.merge(base, 1, Integer::sum);   // 같은 파일 안 동일 행 k번째 등장(TC-FCD-011: 별개 거래)
            out.add(cr.withRawHash(rawHash(Map.of("h", base, "occ", k))));
        }
        return new ParseResult(sha, src.kind(), channel, src.encoding(), src.delimiter(), hm.headerRow(), hm.nRows(),
                profile, score, false, out, errors, warnings, headers, rowsTotal);
    }

    private static RowError err(int rowNo, String column, String code, Object value, String msg) {
        return new RowError(rowNo, column, code, value == null ? "" : PanTools.maskPans(String.valueOf(value)), msg);
    }

    /** 행별 오류 리포트(UTF-8 BOM — Excel 에서 바로 열림) */
    public static byte[] errorsCsv(List<RowError> errors) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("행번호,컬럼,오류코드,원문값,메시지\r\n");
        for (RowError e : errors) {
            sb.append(e.rowNo()).append(',').append(q(e.column())).append(',').append(q(e.code())).append(',')
                    .append(q(e.rawValue())).append(',').append(q(e.message())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * CSV 셀 인용 + 수식 주입 무력화(SEC-FCD-009). 카드사 파일 원문값(가맹점명 등 외부 통제 문자열)이
     * {@code =HYPERLINK(...)} 처럼 = + - @ 탭 CR 로 시작하면 Excel 이 수식으로 실행하므로 앞에 ' 를 붙인다.
     */
    static String q(String s) {
        if (s == null) {
            return "";
        }
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
