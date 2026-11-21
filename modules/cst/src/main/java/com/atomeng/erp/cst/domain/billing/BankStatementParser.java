package com.atomeng.erp.cst.domain.billing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 통장 명세(CSV · XLSX) → 입금 · 출금 줄 (docs/CST_BILLING_COLLECTION_DESIGN.md §12, 순수 도메인 · DB 없음).
 * <ul>
 *   <li>형식은 매직넘버(PK = xlsx)로, 인코딩은 BOM → UTF-8 엄격 → CP949 순서로 판별한다(은행 CSV 는 대부분 CP949).</li>
 *   <li>머리글 행은 앞 20줄 중 "거래일 · 입금 · 출금 · 잔액 · 거래내용" 비슷한 말이 가장 많은 줄이다(안내 문구 줄은 건너뛴다).</li>
 *   <li>금액은 쉼표 · 원 · 공백을 지운 정수(원). 입금 · 출금이 한 열("금액" + 부호 또는 구분 열)인 은행도 받는다.</li>
 *   <li>statement_key = sha256(파일)[:16] + ":" + 줄번호 → 같은 파일을 다시 올려도 DB 유일 제약으로 한 번만.</li>
 *   <li>잔액 열이 있으면 줄마다 "앞 잔액 + 입금 − 출금 = 잔액"을 확인하고 어긋난 줄 번호를 모은다(저장은 하지 않는다).</li>
 * </ul>
 * 계좌번호 · 개인 이름은 이 클래스가 해석하지 않는다. 거래내용은 그대로 넘기고 저장 쪽에서 길이만 자른다.
 */
public final class BankStatementParser {
    private BankStatementParser() {}

    public static final int MAX_ROWS = 200_000;
    public static final int MAX_COLS = 64;

    public enum Direction { IN, OUT }

    public record Line(int rowNo, LocalDate valueDate, Direction direction, BigDecimal amountKrw, String memo,
                       BigDecimal balanceKrw, String lineKey) {}

    public record Mapping(int dateCol, int inCol, int outCol, int amountCol, int signCol, int memoCol, int balanceCol) {}

    public record Result(String fileSha256, String kind, String encoding, int headerRow, List<String> headers, Mapping mapping,
                         List<Line> lines, List<Integer> balanceMismatchRows, List<String> warnings, int rowsTotal, int rowsSkipped) {
        public long inflowCount() {
            return lines.stream().filter(l -> l.direction() == Direction.IN).count();
        }
    }

    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public FormatException(String m) {
            super(m);
        }
    }

    /** 머리글 비슷한 말(정규화: 공백 · 괄호 제거, 소문자) */
    static final Map<String, List<String>> SYNONYMS = new LinkedHashMap<>();
    static {
        SYNONYMS.put("date", List.of("거래일", "거래일자", "거래일시", "일자", "날짜", "거래날짜", "date", "transdate"));
        SYNONYMS.put("in", List.of("입금", "입금액", "입금금액", "맡기신금액", "맡긴금액", "deposit", "credit"));
        SYNONYMS.put("out", List.of("출금", "출금액", "출금금액", "찾으신금액", "찾은금액", "withdrawal", "debit"));
        SYNONYMS.put("amount", List.of("금액", "거래금액", "amount"));
        SYNONYMS.put("sign", List.of("구분", "입출구분", "거래구분", "입출금구분", "type"));
        SYNONYMS.put("memo", List.of("거래내용", "내용", "적요", "기재내용", "보내신분", "받는분", "의뢰인", "비고", "memo", "description"));
        SYNONYMS.put("balance", List.of("잔액", "거래후잔액", "잔고", "balance"));
    }

    private static final Pattern NON_NUM = Pattern.compile("[^0-9.\\-]");
    private static final Pattern DATE_PREFIX = Pattern.compile("^(\\d{4})[.\\-/년 ]?\\s*(\\d{1,2})[.\\-/월 ]?\\s*(\\d{1,2})");
    private static final Pattern DATE_YY = Pattern.compile("^(\\d{2})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})");
    private static final DataFormatter FMT = new DataFormatter(Locale.KOREA);

    public static Result parse(byte[] data) {
        if (data == null || data.length == 0) {
            throw new FormatException("빈 파일");
        }
        String sha = sha256(data);
        String kind = isXlsx(data) ? "XLSX" : "CSV";
        String encoding = "binary";
        List<List<String>> rows;
        if (kind.equals("XLSX")) {
            rows = readXlsx(data);
        } else {
            encoding = detectEncoding(data);
            rows = CsvText.parse(decode(data, encoding));
        }
        if (rows.size() > MAX_ROWS) {
            throw new FormatException("행이 너무 많습니다: " + rows.size() + " > " + MAX_ROWS);
        }
        int headerRow = findHeader(rows);
        List<String> headers = rows.get(headerRow);
        Mapping m = map(headers);
        List<Line> lines = new ArrayList<>();
        List<Integer> mismatch = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int skipped = 0;
        BigDecimal prevBalance = null;
        for (int i = headerRow + 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            int rowNo = i + 1;
            if (r.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            LocalDate d = parseDate(cell(r, m.dateCol()));
            if (d == null) {
                skipped++;
                continue;   // 합계 · 안내 줄
            }
            BigDecimal in = m.inCol() >= 0 ? money(cell(r, m.inCol())) : null;
            BigDecimal out = m.outCol() >= 0 ? money(cell(r, m.outCol())) : null;
            if (m.inCol() < 0 && m.outCol() < 0 && m.amountCol() >= 0) {
                BigDecimal amt = money(cell(r, m.amountCol()));
                String sign = m.signCol() >= 0 ? cell(r, m.signCol()) : "";
                boolean isOut = (amt != null && amt.signum() < 0) || sign.contains("출") || sign.toLowerCase(Locale.ROOT).contains("debit")
                        || sign.toLowerCase(Locale.ROOT).contains("withdraw");
                if (amt != null) {
                    if (isOut) { out = amt.abs(); } else { in = amt.abs(); }
                }
            }
            BigDecimal bal = m.balanceCol() >= 0 ? money(cell(r, m.balanceCol())) : null;
            String memo = m.memoCol() >= 0 ? cell(r, m.memoCol()).trim() : "";
            String key = sha.substring(0, 16) + ":" + rowNo;
            if (in != null && in.signum() > 0) {
                lines.add(new Line(rowNo, d, Direction.IN, in, memo, bal, key));
            } else if (out != null && out.signum() > 0) {
                lines.add(new Line(rowNo, d, Direction.OUT, out, memo, bal, key));
            } else {
                skipped++;
                continue;
            }
            if (bal != null && prevBalance != null) {
                BigDecimal expect = prevBalance.add(in == null ? BigDecimal.ZERO : in).subtract(out == null ? BigDecimal.ZERO : out);
                if (expect.compareTo(bal) != 0) {
                    mismatch.add(rowNo);
                }
            }
            if (bal != null) {
                prevBalance = bal;
            }
        }
        if (lines.isEmpty()) {
            warnings.add("입금 · 출금 줄을 하나도 읽지 못했습니다 — 머리글 매핑을 확인하세요: " + headers);
        }
        if (!mismatch.isEmpty()) {
            warnings.add("잔액 검증 불일치 " + mismatch.size() + "줄 (명세서가 역순이거나 중간 줄이 빠졌을 수 있음)");
        }
        return new Result(sha, kind, encoding, headerRow + 1, headers, m, lines, mismatch, warnings, rows.size(), skipped);
    }

    // ------------------------------------------------------------------ 머리글

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s()\\[\\]_\\-·:]+", "").toLowerCase(Locale.ROOT);
    }

    static int findHeader(List<List<String>> rows) {
        int best = -1;
        int bestHits = 0;
        for (int i = 0; i < Math.min(20, rows.size()); i++) {
            int hits = 0;
            for (String c : rows.get(i)) {
                String n = norm(c);
                for (List<String> syn : SYNONYMS.values()) {
                    if (syn.stream().anyMatch(x -> norm(x).equals(n))) {
                        hits++;
                        break;
                    }
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = i;
            }
        }
        if (best < 0 || bestHits < 2) {
            throw new FormatException("머리글 행을 찾지 못했습니다(거래일 · 입금 · 출금 · 잔액 · 거래내용 중 2개 이상 필요)");
        }
        return best;
    }

    static Mapping map(List<String> headers) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int c = 0; c < headers.size() && c < MAX_COLS; c++) {
            String n = norm(headers.get(c));
            for (Map.Entry<String, List<String>> e : SYNONYMS.entrySet()) {
                if (!idx.containsKey(e.getKey()) && e.getValue().stream().anyMatch(x -> norm(x).equals(n))) {
                    idx.put(e.getKey(), c);
                }
            }
        }
        if (!idx.containsKey("date")) {
            throw new FormatException("거래일 열이 없습니다: " + headers);
        }
        if (!idx.containsKey("in") && !idx.containsKey("out") && !idx.containsKey("amount")) {
            throw new FormatException("입금 · 출금(또는 금액) 열이 없습니다: " + headers);
        }
        return new Mapping(idx.get("date"), idx.getOrDefault("in", -1), idx.getOrDefault("out", -1), idx.getOrDefault("amount", -1),
                idx.getOrDefault("sign", -1), idx.getOrDefault("memo", -1), idx.getOrDefault("balance", -1));
    }

    // ------------------------------------------------------------------ 값

    static String cell(List<String> r, int c) {
        return c < 0 || c >= r.size() || r.get(c) == null ? "" : r.get(c);
    }

    static BigDecimal money(String s) {
        if (s == null) {
            return null;
        }
        String t = NON_NUM.matcher(s.replace("−", "-")).replaceAll("");
        if (t.isEmpty() || t.equals("-") || t.equals(".")) {
            return null;
        }
        try {
            return new BigDecimal(t).setScale(0, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        Matcher m = DATE_PREFIX.matcher(t);
        if (m.find()) {
            return safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        Matcher y = DATE_YY.matcher(t);
        if (y.find()) {
            return safeDate(2000 + Integer.parseInt(y.group(1)), Integer.parseInt(y.group(2)), Integer.parseInt(y.group(3)));
        }
        try {
            return LocalDate.parse(t.substring(0, Math.min(10, t.length())), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static LocalDate safeDate(int y, int mo, int d) {
        try {
            return LocalDate.of(y, mo, d);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 파일

    static boolean isXlsx(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    static String detectEncoding(byte[] d) {
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            return "UTF-8-BOM";
        }
        if (decodes(d, StandardCharsets.UTF_8)) {
            return "UTF-8";
        }
        if (decodes(d, Charset.forName("MS949"))) {
            return "MS949";
        }
        throw new FormatException("인코딩을 판별하지 못했습니다(UTF-8 · CP949 모두 실패)");
    }

    private static boolean decodes(byte[] d, Charset cs) {
        try {
            cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d, 0, Math.min(d.length, 65536)));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    static String decode(byte[] d, String enc) {
        if (enc.equals("UTF-8-BOM")) {
            return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        }
        return new String(d, enc.equals("MS949") ? Charset.forName("MS949") : StandardCharsets.UTF_8);
    }

    static List<List<String>> readXlsx(byte[] data) {
        List<List<String>> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sh = wb.getSheetAt(0);
            for (Row row : sh) {
                if (out.size() > MAX_ROWS) {
                    throw new FormatException("행이 너무 많습니다 > " + MAX_ROWS);
                }
                List<String> cells = new ArrayList<>();
                int last = Math.min(row.getLastCellNum(), MAX_COLS);
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : cellText(cell));
                }
                out.add(cells);
            }
        } catch (IOException e) {
            throw new FormatException("XLSX 를 읽지 못했습니다: " + e.getMessage());
        }
        return out;
    }

    private static String cellText(Cell cell) {
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return new BigDecimal(cell.getNumericCellValue()).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        return FMT.formatCellValue(cell);
    }

    static String sha256(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 작은 CSV 읽기(RFC 4180 따옴표 · 구분자 자동: 쉼표 · 탭 · 세미콜론 중 첫 10줄에서 가장 많은 것) */
    static final class CsvText {
        private CsvText() {}

        static List<List<String>> parse(String text) {
            char delim = sniff(text);
            List<List<String>> rows = new ArrayList<>();
            List<String> cur = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (q) {
                    if (ch == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') { sb.append('"'); i++; } else { q = false; }
                    } else {
                        sb.append(ch);
                    }
                } else if (ch == '"') {
                    q = true;
                } else if (ch == delim) {
                    cur.add(sb.toString()); sb.setLength(0);
                } else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                    cur.add(sb.toString()); sb.setLength(0); rows.add(cur); cur = new ArrayList<>();
                    if (rows.size() > MAX_ROWS) { throw new FormatException("행이 너무 많습니다 > " + MAX_ROWS); }
                } else {
                    sb.append(ch);
                }
            }
            if (sb.length() > 0 || !cur.isEmpty()) { cur.add(sb.toString()); rows.add(cur); }
            return rows;
        }

        static char sniff(String text) {
            String[] head = text.split("\r?\n", 11);
            long comma = 0, tab = 0, semi = 0;
            for (int i = 0; i < Math.min(10, head.length); i++) {
                comma += head[i].chars().filter(c -> c == ',').count();
                tab += head[i].chars().filter(c -> c == '\t').count();
                semi += head[i].chars().filter(c -> c == ';').count();
            }
            return tab > comma && tab >= semi ? '\t' : semi > comma ? ';' : ',';
        }
    }
}
