package com.atomeng.erp.fcd.domain.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * CSV · XLSX · XLS(BIFF8) · HTML-as-XLS 를 같은 행 스트림(List&lt;List&lt;Object&gt;&gt;)으로 만든다 — 채널 차이는 여기서 끝난다
 * (설계 B7.1 Step 1–3, Python {@code atomeng_common.ingest.rowsource · text} 이식).
 * 셀 값은 String 또는 {@link LocalDateTime}(Excel 날짜 셀).
 * <ul>
 *   <li>형식은 매직넘버로 판별(확장자 무시)</li>
 *   <li>인코딩: BOM → UTF-8 엄격 → CP949(64KB 프로브, 2바이트 경계 절단 보정)</li>
 *   <li>XLSX: POI XSSF SAX 이벤트 파서(스트리밍) — DataFormatter 없이 숫자를 읽으면 승인번호 선행 0 이 사라지므로
 *       표시형식 '0000…' 이면 0 채움 복원</li>
 * </ul>
 */
public final class RowSource {

    public record Result(String kind, List<List<Object>> rows, String encoding, String delimiter,
                         double replacementRatio, String sheet, List<String> warnings) {}

    public static final class FormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public FormatException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private RowSource() {}

    public static String sniffKind(byte[] data) {
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
            return "XLSX";
        }
        if (data.length >= 4 && (data[0] & 0xff) == 0xd0 && (data[1] & 0xff) == 0xcf && (data[2] & 0xff) == 0x11
                && (data[3] & 0xff) == 0xe0) {
            return "XLS";
        }
        String head = new String(data, 0, Math.min(512, data.length), StandardCharsets.ISO_8859_1).stripLeading()
                .toLowerCase(Locale.ROOT);
        if (head.startsWith("<table") || head.startsWith("<html") || head.startsWith("<!doctype html")) {
            return "HTML_AS_XLS";
        }
        return "CSV";
    }

    public static boolean hasBom(byte[] d) {
        return d.length >= 3 && (d[0] & 0xff) == 0xef && (d[1] & 0xff) == 0xbb && (d[2] & 0xff) == 0xbf;
    }

    private static boolean decodes(byte[] data, int n, Charset cs) {
        CharsetDecoder dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            dec.decode(ByteBuffer.wrap(data, 0, n));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** BOM → UTF-8 엄격 → CP949. 반환: UTF-8-BOM | UTF-8 | CP949 */
    public static String detectEncoding(byte[] data) {
        if (hasBom(data)) {
            return "UTF-8-BOM";
        }
        int probe = 64 * 1024;
        int n = Math.min(data.length, probe);
        if (n < data.length) {
            n = Math.max(0, n - 3);   // 다중바이트 절단 방지
        }
        if (decodes(data, n, StandardCharsets.UTF_8)) {
            return "UTF-8";
        }
        Charset cp949 = Charset.forName("x-windows-949");
        for (int cut : new int[] {0, 1}) {   // 프로브 경계에서 2바이트 문자가 잘린 경우 1바이트 줄여 재시도
            if (decodes(data, Math.max(0, n - cut), cp949)) {
                return "CP949";
            }
        }
        throw new FormatException("E-ENCODING", "UTF-8/CP949 모두 디코딩 실패");
    }

    static String decode(byte[] data, String enc) {
        return switch (enc) {
            case "UTF-8-BOM" -> new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
            case "UTF-8" -> new String(data, StandardCharsets.UTF_8);
            default -> new String(data, Charset.forName("x-windows-949"));
        };
    }

    /** SEC-FCD-007: 행 · 열 상한(빈 행 보존 패딩 · 압축 폭탄으로 메모리 고갈 방지). 카드사 월 명세서는 수만 행 수준. */
    public static final int MAX_ROWS = 200_000;
    public static final int MAX_COLS = 512;
    /** XLSX 압축 해제 시 항목 하나의 최대 크기(POI 기본 4GB → 200MB) */
    public static final long MAX_XLSX_ENTRY_BYTES = 200L * 1024 * 1024;

    static void checkRows(long n) {
        if (n > MAX_ROWS) {
            throw new FormatException("E-TOO-MANY-ROWS", "행 수가 상한(" + MAX_ROWS + ")을 넘습니다");
        }
    }

    static void checkCols(long n) {
        if (n > MAX_COLS) {
            throw new FormatException("E-TOO-MANY-COLS", "열 수가 상한(" + MAX_COLS + ")을 넘습니다");
        }
    }

    public static Result read(byte[] data) {
        String kind = sniffKind(data);
        List<String> warnings = new ArrayList<>();
        switch (kind) {
            case "XLSX" -> {
                return readXlsx(data, warnings);
            }
            case "XLS" -> {
                return readXls(data, warnings);
            }
            case "HTML_AS_XLS" -> {
                String enc = detectEncoding(data);
                String text = decode(data, enc);
                warnings.add("W-HTML-XLS");
                return new Result(kind, htmlRows(text), enc, null, replacement(text), null, warnings);
            }
            default -> {
                String enc = detectEncoding(data);
                String text = decode(data, enc);
                double bad = replacement(text);
                if (bad > 0.01) {
                    warnings.add(String.format(Locale.ROOT, "W-REPLACEMENT-CHARS %.2f%%", bad * 100));
                }
                String delim = detectDelimiter(text);
                List<List<Object>> rows = new ArrayList<>();
                for (List<String> r : CsvParser.parse(text, delim.charAt(0))) {
                    checkRows(rows.size() + 1L);
                    checkCols(r.size());
                    rows.add(new ArrayList<>(r));
                }
                return new Result("CSV", rows, enc, delim, bad, null, warnings);
            }
        }
    }

    private static double replacement(String text) {
        long bad = text.chars().filter(c -> c == '�').count();
        return (double) bad / Math.max(1, text.length());
    }

    /** 앞 20행에서 행별 구분자 수 분산(상위 절반)이 가장 작고 평균 ≥ 2 인 문자 (따옴표 안 구분자는 파서로 정확히 셈) */
    public static String detectDelimiter(String text) {
        String[] lines = text.split("\r\n|\n|\r", -1);
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < Math.min(40, lines.length); i++) {
            if (i > 0) {
                sample.append('\n');
            }
            sample.append(lines[i]);
        }
        String best = ",";
        double[] bestKey = null;
        for (char d : new char[] {',', '\t', ';', '|'}) {
            List<List<String>> rows = CsvParser.parse(sample.toString(), d);
            List<Integer> counts = new ArrayList<>();
            for (int i = 0; i < Math.min(20, rows.size()); i++) {
                List<String> r = rows.get(i);
                if (!(r.size() == 1 && r.get(0).isEmpty()) && !r.isEmpty()) {
                    counts.add(r.size() - 1);
                }
            }
            if (counts.isEmpty()) {
                continue;
            }
            double mean = counts.stream().mapToInt(Integer::intValue).average().orElse(0);
            if (mean < 2) {
                continue;
            }
            List<Integer> sorted = new ArrayList<>(counts);
            sorted.sort(null);
            List<Integer> top = sorted.subList(sorted.size() / 2, sorted.size());
            double var = 0;
            if (top.size() > 1) {
                double m = top.stream().mapToInt(Integer::intValue).average().orElse(0);
                for (int x : top) {
                    var += (x - m) * (x - m);
                }
                var /= top.size();
            }
            double[] key = {var, -mean};
            if (bestKey == null || key[0] < bestKey[0] || (key[0] == bestKey[0] && key[1] < bestKey[1])) {
                best = String.valueOf(d);
                bestKey = key;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ XLSX (SAX 스트리밍)

    private static final String DT_MARK = "\u0000DT:";
    private static final Pattern ZEROS = Pattern.compile("0+");

    /** 숫자 셀 → Python _xlsx_cell 과 같은 문자열 / 날짜 셀 → 표식(DT_MARK + ISO) */
    static final class PyCellFormatter extends DataFormatter {
        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return formatNumeric(value, formatIndex, formatString);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString, boolean use1904) {
            return formatNumeric(value, formatIndex, formatString);
        }

        static String formatNumeric(double v, int formatIndex, String fmt) {
            if (fmt != null && DateUtil.isADateFormat(formatIndex, fmt) && DateUtil.isValidExcelDate(v)) {
                return DT_MARK + DateUtil.getLocalDateTime(v).withNano(0);
            }
            boolean integral = v == Math.rint(v) && !Double.isInfinite(v);
            if (fmt != null && ZEROS.matcher(fmt).matches() && integral) {
                String s = Long.toString((long) v);
                return s.length() >= fmt.length() ? s : "0".repeat(fmt.length() - s.length()) + s;
            }
            if (integral && Math.abs(v) < 9.0e18) {
                return Long.toString((long) v);
            }
            return com.atomeng.erp.fcd.domain.rules.PyFormat.floatRepr(v);
        }
    }

    private static Object cellValue(String formatted) {
        if (formatted == null) {
            return "";
        }
        if (formatted.startsWith(DT_MARK)) {
            return LocalDateTime.parse(formatted.substring(DT_MARK.length()));
        }
        return formatted.strip();
    }

    private static Result readXlsx(byte[] data, List<String> warnings) {
        ZipSecureFile.setMinInflateRatio(0.01);   // zip bomb 방지
        ZipSecureFile.setMaxEntrySize(MAX_XLSX_ENTRY_BYTES);
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(data))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator it = (XSSFReader.SheetIterator) reader.getSheetsData();
            List<List<Object>> best = null;
            String bestName = null;
            int bestCount = -1;
            while (it.hasNext()) {
                try (InputStream sheet = it.next()) {
                    String name = it.getSheetName();
                    List<List<Object>> rows = new ArrayList<>();
                    SheetContentsHandler h = new SheetContentsHandler() {
                        List<Object> cur;
                        int lastRow = -1;

                        @Override
                        public void startRow(int rowNum) {
                            checkRows(rowNum + 1L);
                            while (lastRow + 1 < rowNum) {   // 빈 행 보존(행 번호 = 파일 행 번호)
                                rows.add(new ArrayList<>());
                                lastRow++;
                            }
                            cur = new ArrayList<>();
                        }

                        @Override
                        public void endRow(int rowNum) {
                            rows.add(cur);
                            lastRow = rowNum;
                        }

                        @Override
                        public void cell(String ref, String formattedValue, XSSFComment comment) {
                            int col = new CellReference(ref).getCol();
                            checkCols(col + 1L);
                            while (cur.size() < col) {
                                cur.add("");
                            }
                            cur.add(cellValue(formattedValue));
                        }
                    };
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, h, new PyCellFormatter(),
                            false));
                    parser.parse(new InputSource(sheet));
                    int width = rows.stream().mapToInt(List::size).max().orElse(0);
                    rows.forEach(r -> {
                        while (r.size() < width) {
                            r.add("");
                        }
                    });
                    int nonEmpty = (int) rows.stream().filter(r -> r.stream().anyMatch(c -> !"".equals(c))).count();
                    if (nonEmpty > bestCount) {
                        best = rows;
                        bestName = name;
                        bestCount = nonEmpty;
                    }
                }
            }
            return new Result("XLSX", best == null ? List.of() : best, null, null, 0, bestName, warnings);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SAXException | ParserConfigurationException | org.apache.poi.openxml4j.exceptions.OpenXML4JException e) {
            throw new FormatException("E-XLSX", "XLSX 파싱 실패: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ XLS (BIFF8, POI HSSF)

    private static Result readXls(byte[] data, List<String> warnings) {
        try (HSSFWorkbook wb = new HSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet best = null;
            int bestCount = -1;
            for (Sheet s : wb) {
                int n = s.getPhysicalNumberOfRows();
                if (n > bestCount) {
                    best = s;
                    bestCount = n;
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            if (best != null) {
                int width = 0;
                checkRows(best.getLastRowNum() + 1L);
                for (Row r : best) {
                    width = Math.max(width, r.getLastCellNum());
                }
                checkCols(width);
                for (int i = 0; i <= best.getLastRowNum(); i++) {
                    Row r = best.getRow(i);
                    List<Object> out = new ArrayList<>();
                    for (int c = 0; c < width; c++) {
                        Cell cell = r == null ? null : r.getCell(c);
                        out.add(xlsCell(cell));
                    }
                    rows.add(out);
                }
            }
            return new Result("XLS", rows, null, null, 0, best == null ? null : best.getSheetName(), warnings);
        } catch (IOException e) {
            throw new FormatException("E-XLS", "XLS(BIFF8) 파싱 실패: " + e.getMessage());
        }
    }

    private static Object xlsCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (t) {
            case NUMERIC -> cellValue(PyCellFormatter.formatNumeric(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString()));
            case STRING -> cell.getStringCellValue().strip();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ HTML 표

    private static final Pattern TR = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern TD = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");

    static List<List<Object>> htmlRows(String html) {
        List<List<Object>> rows = new ArrayList<>();
        Matcher tr = TR.matcher(html);
        while (tr.find()) {
            List<Object> row = new ArrayList<>();
            checkRows(rows.size() + 1L);
            Matcher td = TD.matcher(tr.group(1));
            while (td.find()) {
                row.add(unescape(TAG.matcher(td.group(1)).replaceAll("")).strip());
            }
            rows.add(row);
        }
        return rows;
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
