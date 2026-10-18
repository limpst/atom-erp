package com.atomeng.erp.rpt;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.adapter.out.pdf.GotenbergPdfConverter;
import com.atomeng.erp.rpt.adapter.out.pdf.StubPdfConverter;
import com.atomeng.erp.rpt.application.port.PdfConverterPort;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.drawing.DxfR12Writer;
import com.atomeng.erp.rpt.domain.layout.AnchorLayout;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 망도 DXF(R12) · anchors 레이아웃 · PDF 변환 포트(스텁 · Gotenberg WireMock) · 상태 머신 단위 테스트 */
class DrawingAndPdfTest {

    static WireMockServer gotenberg;

    @BeforeAll
    static void start() {
        gotenberg = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        gotenberg.start();
    }

    @AfterAll
    static void stop() {
        gotenberg.stop();
    }

    static SnapshotDocument golden(String id) {
        try (InputStream in = DrawingAndPdfTest.class.getResourceAsStream("/golden/cases/" + id + ".json")) {
            return RptJson.MAPPER.treeToValue(RptJson.MAPPER.readTree(in).get("snapshot"), SnapshotDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** DXF 그룹코드/값 쌍 파서(테스트용 최소 리더) */
    static List<String[]> pairs(String dxf) {
        String[] lines = dxf.split("\r\n", -1);
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i + 1 < lines.length; i += 2) {
            out.add(new String[] {lines[i].strip(), lines[i + 1]});
        }
        return out;
    }

    @Test
    @DisplayName("FR-RPT-004: 외관조사망도 DXF R12 — 섹션 구조 · AC1009 · 레이어 표 · 균열 폴리라인 정점 수 · 엔티티 레이어 정의 · 지수 표기 없음")
    void dxfR12Structure() {
        SnapshotDocument doc = golden("GOLD-04");
        List<SnapshotDocument.Defect> floor = doc.defects().stream()
                .filter(d -> d.buildingCode().equals("B01") && d.floorCode().equals("1F")).toList();
        assertThat(floor).isNotEmpty();
        DxfR12Writer.Sheet sheet = DxfR12Writer.write("B01", "1F", floor);
        String dxf = sheet.dxf();
        List<String[]> p = pairs(dxf);
        assertThat(dxf.split("\r\n", -1).length % 2).isEqualTo(1);   // 쌍 단위 + 마지막 개행
        assertThat(p.get(p.size() - 1)).containsExactly("0", "EOF");
        // 섹션 순서 · 짝
        List<String> sections = new ArrayList<>();
        int open = 0;
        for (int i = 0; i < p.size(); i++) {
            if (p.get(i)[0].equals("0") && p.get(i)[1].equals("SECTION")) {
                open++;
                sections.add(p.get(i + 1)[1]);
            } else if (p.get(i)[0].equals("0") && p.get(i)[1].equals("ENDSEC")) {
                open--;
            }
            assertThat(open).isBetween(0, 1);
        }
        assertThat(sections).containsExactly("HEADER", "TABLES", "BLOCKS", "ENTITIES");
        assertThat(dxf).contains("$ACADVER\r\n  1\r\nAC1009");
        // 레이어 표
        Set<String> layers = new HashSet<>();
        for (int i = 0; i + 1 < p.size(); i++) {
            if (p.get(i)[0].equals("0") && p.get(i)[1].equals("LAYER")) {
                layers.add(p.get(i + 1)[1]);
            }
        }
        assertThat(layers).containsExactlyInAnyOrderElementsOf(DxfR12Writer.LAYERS);
        // 엔티티: 레이어 정의 · 균열 정점 수
        int entStart = indexOf(p, "ENTITIES");
        Map<String, Integer> byLayer = new HashMap<>();
        List<Integer> crackVertices = new ArrayList<>();
        String current = null;
        int vertices = 0;
        for (int i = entStart; i < p.size(); i++) {
            String[] x = p.get(i);
            if (x[0].equals("0")) {
                if ("POLYLINE".equals(current) && x[1].equals("SEQEND")) {
                    crackVertices.add(vertices);
                }
                if (x[1].equals("POLYLINE")) {
                    vertices = 0;
                }
                if (x[1].equals("VERTEX")) {
                    vertices++;
                }
                if (!x[1].equals("VERTEX") && !x[1].equals("SEQEND")) {
                    current = x[1];
                }
            }
            if (x[0].equals("8")) {
                assertThat(layers).as("엔티티 레이어 정의됨").contains(x[1]);
                byLayer.merge(x[1], 1, Integer::sum);
            }
            if (x[0].equals("10") || x[0].equals("20") || x[0].equals("40")) {
                assertThat(x[1]).matches("-?\\d+(\\.\\d+)?");
            }
        }
        long cracks = floor.stream().filter(d -> d.defectType().equals("CRACK")).count();
        assertThat(count(p, "POLYLINE")).isGreaterThanOrEqualTo(cracks + 1);   // 외곽 + 균열 + 박리 · 철근노출
        assertThat(count(p, "TEXT")).isEqualTo(floor.size() + 2);             // 결함 라벨 + 범례 2줄
        assertThat(count(p, "SEQEND")).isEqualTo(count(p, "POLYLINE"));
        List<Integer> expected = floor.stream().filter(d -> d.defectType().equals("CRACK"))
                .sorted(java.util.Comparator.comparing(SnapshotDocument.Defect::id))
                .map(d -> Math.max(2, d.polyline().size())).toList();
        assertThat(crackVertices).containsSubsequence(expected);
        assertThat(sheet.defectCount()).isEqualTo(floor.size());
        assertThat(DxfR12Writer.write("B01", "1F", floor).dxf()).isEqualTo(dxf);   // 결정적
    }

    private static int indexOf(List<String[]> p, String section) {
        for (int i = 0; i + 1 < p.size(); i++) {
            if (p.get(i)[1].equals("SECTION") && p.get(i + 1)[1].equals(section)) {
                return i;
            }
        }
        throw new AssertionError(section);
    }

    private static long count(List<String[]> p, String entity) {
        return p.stream().filter(x -> x[0].equals("0") && x[1].equals(entity)).count();
    }

    @Test
    @DisplayName("anchors 레이아웃: 블록마다 페이지 · rect(mm) · 텍스트 해시, 긴 표는 쪽마다 rect, 결정적")
    void anchorLayout() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(List.of("B01", "1F", "COLUMN", "CRACK", String.valueOf(i)));
        }
        UUID t = UUID.randomUUID();
        UUID p = UUID.randomUUID();
        var model = new com.atomeng.erp.rpt.domain.hwpx.RenderModel(Map.of(), Map.of(), List.of(
                new com.atomeng.erp.rpt.domain.hwpx.RenderModel.Section("ch4.quantities", "4", List.of(
                        new com.atomeng.erp.rpt.domain.hwpx.RenderModel.Block(t, "TABLE", "표", List.of("a"), rows, null),
                        new com.atomeng.erp.rpt.domain.hwpx.RenderModel.Block(p, "PARAGRAPH", "가".repeat(100),
                                List.of(), List.of(), null)))), Map.of());
        AnchorLayout.AnchorSet a = AnchorLayout.layout(UUID.randomUUID(), model);
        assertThat(a.anchors()).hasSize(2);
        assertThat(a.anchors().get(0).page()).isEqualTo(2);
        assertThat(a.anchors().get(0).rects()).hasSizeGreaterThanOrEqualTo(2);   // 60행 → 쪽 넘김
        assertThat(a.anchors().get(1).textHash()).isEqualTo(RptJson.textHash("가".repeat(100)));
        assertThat(a.anchors().get(1).rects().get(0).h()).isEqualByComparingTo("24");   // ceil(100/40)=3줄 → 3×7+3
        assertThat(a.pageCount()).isGreaterThanOrEqualTo(3);
        assertThat(AnchorLayout.layout(a.revisionId(), model).sha256()).isEqualTo(a.sha256());
    }

    @Test
    @DisplayName("PDF 스텁: 추정 쪽수만큼 페이지 · xref 오프셋이 실제 객체 위치와 일치 · %%EOF")
    void stubPdf() {
        PdfConverterPort.Result r = new StubPdfConverter().convert("report.hwpx", "hwpx".getBytes(StandardCharsets.UTF_8), 3);
        String pdf = new String(r.pdf(), StandardCharsets.ISO_8859_1);
        assertThat(pdf).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 3");
        assertThat(r.pageCount()).isEqualTo(3);
        assertThat(r.converter()).isEqualTo("STUB");
        int xref = Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref") + 10, pdf.lastIndexOf("\n%%EOF")).strip());
        assertThat(pdf.substring(xref)).startsWith("xref");
        Matcher m = Pattern.compile("(\\d{10}) 00000 n").matcher(pdf.substring(xref));
        int obj = 1;
        while (m.find()) {
            int off = Integer.parseInt(m.group(1));
            assertThat(pdf.substring(off)).startsWith(obj + " 0 obj");
            obj++;
        }
        assertThat(obj - 1).isEqualTo(3 + 3 * 2);   // catalog · pages · font + (page + content) × 3
    }

    @Test
    @DisplayName("B8 Gotenberg 어댑터(WireMock): multipart files=report.hwpx 로 /forms/libreoffice/convert 호출 · 쪽수 계산 · 5xx → RPT-503-001")
    void gotenbergAdapter() {
        byte[] pdf = new StubPdfConverter().convert("x.hwpx", new byte[] {1, 2, 3}, 2).pdf();
        gotenberg.stubFor(post(urlEqualTo("/forms/libreoffice/convert"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/pdf").withBody(pdf)));
        GotenbergPdfConverter c = new GotenbergPdfConverter(gotenberg.baseUrl(), Duration.ofSeconds(5));
        PdfConverterPort.Result r = c.convert("report.hwpx", "PK-synthetic".getBytes(StandardCharsets.US_ASCII), 9);
        assertThat(r.converter()).isEqualTo("GOTENBERG");
        assertThat(r.pageCount()).isEqualTo(2);
        assertThat(r.pdf()).isEqualTo(pdf);
        gotenberg.verify(postRequestedFor(urlEqualTo("/forms/libreoffice/convert"))
                .withRequestBodyPart(aMultipart().withName("files").withHeader("Content-Disposition",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("filename=\"report.hwpx\""))
                        .withBody(equalTo("PK-synthetic")).build()));

        gotenberg.resetAll();
        gotenberg.stubFor(post(urlEqualTo("/forms/libreoffice/convert")).willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(() -> c.convert("report.hwpx", new byte[] {1}, 1)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("RPT-503-001");
        gotenberg.resetAll();
        gotenberg.stubFor(post(urlEqualTo("/forms/libreoffice/convert")).willReturn(aResponse().withStatus(200)
                .withBody("<html>not pdf</html>")));
        assertThatThrownBy(() -> c.convert("report.hwpx", new byte[] {1}, 1)).hasMessageContaining("PDF 가 아님");
    }

    @Test
    @DisplayName("A6 상태 머신: 허용 전이만 통과(81 조합), 발행 이후 동결")
    void stateMachine() {
        int allowed = 0;
        for (ReportStatus a : ReportStatus.values()) {
            for (ReportStatus b : ReportStatus.values()) {
                if (a.canMoveTo(b)) {
                    allowed++;
                } else {
                    assertThatThrownBy(() -> a.transitionTo(b)).isInstanceOf(DomainException.class)
                            .hasMessageContaining(a + " → " + b);
                }
            }
        }
        assertThat(allowed).isEqualTo(13);
        assertThat(ReportStatus.PUBLISHED.isFrozen()).isTrue();
        assertThat(ReportStatus.APPROVED.isFrozen()).isFalse();
        assertThat(ReportStatus.SUPERSEDED.canMoveTo(ReportStatus.PUBLISHED)).isFalse();
    }
}
