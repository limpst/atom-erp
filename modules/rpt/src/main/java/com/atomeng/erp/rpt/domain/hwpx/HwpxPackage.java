package com.atomeng.erp.rpt.domain.hwpx;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.domain.RptCodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * HWPX 패키지 = OWPML(KS X 6101) XML 들을 담은 ZIP (설계 06 B7.3). 엔트리 순서를 보존하고,
 * 저장 시 {@code mimetype}(application/hwp+zip)을 <b>무압축(STORED) 첫 엔트리</b>로 쓴다(OCF 규칙).
 */
public final class HwpxPackage {

    public static final String MIMETYPE = "application/hwp+zip";
    public static final String SECTION0 = "Contents/section0.xml";
    public static final String CONTENT_HPF = "Contents/content.hpf";
    public static final String HEADER = "Contents/header.xml";

    private final LinkedHashMap<String, byte[]> entries;

    public HwpxPackage(Map<String, byte[]> entries) {
        this.entries = new LinkedHashMap<>(entries);
    }

    public Map<String, byte[]> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public byte[] get(String name) {
        return entries.get(name);
    }

    public String text(String name) {
        byte[] b = entries.get(name);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    public HwpxPackage with(String name, byte[] content) {
        LinkedHashMap<String, byte[]> m = new LinkedHashMap<>(entries);
        m.put(name, content);
        return new HwpxPackage(m);
    }

    /** classpath 템플릿 디렉터리(entries.txt 에 엔트리 순서) → 패키지 */
    public static HwpxPackage fromClasspath(String base) {
        String root = base.startsWith("classpath:") ? base.substring("classpath:".length()) : base;
        String listing = new String(resource(root + "/entries.txt"), StandardCharsets.UTF_8);
        LinkedHashMap<String, byte[]> m = new LinkedHashMap<>();
        for (String line : listing.split("\\R")) {
            String name = line.strip();
            if (!name.isEmpty()) {
                m.put(name, resource(root + "/" + name));
            }
        }
        return new HwpxPackage(m);
    }

    private static byte[] resource(String path) {
        try (InputStream in = HwpxPackage.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new DomainException(RptCodes.TEMPLATE_NOT_FOUND, "템플릿 자원 없음: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static HwpxPackage read(byte[] zip) {
        LinkedHashMap<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    m.put(e.getName(), zin.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new DomainException(RptCodes.HWPX_INVALID, "ZIP 읽기 실패: " + e.getMessage());
        }
        return new HwpxPackage(m);
    }

    /** ZIP 엔트리 메타(이름 · 압축 방식) — 검증기용 */
    public record EntryInfo(String name, int method) {}

    public static List<EntryInfo> entryInfos(byte[] zip) {
        List<EntryInfo> out = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                out.add(new EntryInfo(e.getName(), e.getMethod()));
            }
        } catch (IOException e) {
            throw new DomainException(RptCodes.HWPX_INVALID, "ZIP 읽기 실패: " + e.getMessage());
        }
        return out;
    }

    public byte[] toZip() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            byte[] mime = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
            ZipEntry me = new ZipEntry("mimetype");
            me.setMethod(ZipEntry.STORED);
            me.setSize(mime.length);
            me.setCompressedSize(mime.length);
            CRC32 crc = new CRC32();
            crc.update(mime);
            me.setCrc(crc.getValue());
            me.setTime(0L);
            zos.putNextEntry(me);
            zos.write(mime);
            zos.closeEntry();
            for (var e : entries.entrySet()) {
                if (e.getKey().equals("mimetype")) {
                    continue;
                }
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setTime(0L);   // 결정적 산출물(같은 입력 = 같은 바이트)
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
}
