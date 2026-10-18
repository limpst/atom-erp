package com.atomeng.erp.fcd.domain.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 계열 CSV 파서 — Python csv.reader(excel 방언, strict=False)와 같은 동작:
 * 따옴표 필드 · "" 이스케이프 · 따옴표 안 줄바꿈 · \r\n/\n/\r 행 끝 · 빈 줄은 빈 행.
 */
public final class CsvParser {

    private CsvParser() {}

    public static List<List<String>> parse(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        int n = text.length();
        int i = 0;
        boolean inQuotes = false;
        boolean fieldStart = true;
        boolean rowHasContent = false;
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                f.append(c);
                i++;
                continue;
            }
            if (c == '"' && fieldStart) {
                inQuotes = true;
                fieldStart = false;
                rowHasContent = true;
                i++;
                continue;
            }
            if (c == delim) {
                row.add(f.toString());
                f.setLength(0);
                fieldStart = true;
                rowHasContent = true;
                i++;
                continue;
            }
            if (c == '\r' || c == '\n') {
                if (rowHasContent || f.length() > 0) {
                    row.add(f.toString());
                }
                rows.add(row);
                row = new ArrayList<>();
                f.setLength(0);
                fieldStart = true;
                rowHasContent = false;
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                i++;
                continue;
            }
            f.append(c);
            fieldStart = false;
            rowHasContent = true;
            i++;
        }
        if (rowHasContent || f.length() > 0) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }
}
