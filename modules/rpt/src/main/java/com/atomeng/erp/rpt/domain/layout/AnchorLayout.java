package com.atomeng.erp.rpt.domain.layout;

import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel.Block;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel.Section;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 리비전 anchors.json 산출 — 블록마다 페이지 · 위치(rect, mm, 왼쪽 위 원점) · 텍스트 해시.
 * QAR 코멘트가 "몇 쪽 어느 문단"을 안정적으로 가리키게 하는 앵커다(QAR 연동 전제).
 * <p>이번 단계는 HWPX 와 같은 블록 순서로 A4(210×297, 본문 170×257) 흐름 배치를 <b>추정</b>한다(source=ESTIMATED):
 * 1쪽 = 표지(제목 · 메타 · rule 표), 섹션은 2쪽부터, 제목 12mm · 문단 ceil(글자/40)×7+3mm · 표 머리 8 + 행 7mm(쪽 넘김 시 rect
 * 분할) · 그림 110+캡션 7mm(쪽 넘김 없음). 실제 PDF 글자 좌표 추출(PDFBox)로의 교체는 렌더 워커 단계.</p>
 */
public final class AnchorLayout {

    public static final BigDecimal LEFT = new BigDecimal("20");
    public static final BigDecimal TOP = new BigDecimal("20");
    public static final BigDecimal WIDTH = new BigDecimal("170");
    public static final BigDecimal BOTTOM = new BigDecimal("277");

    public record Rect(int page, BigDecimal x, BigDecimal y, BigDecimal w, BigDecimal h) {}

    public record Anchor(UUID blockId, String sectionKey, String kind, int page, List<Rect> rects, String textHash) {}

    public record AnchorSet(UUID revisionId, String source, String unit, int pageCount, List<Anchor> anchors) {

        public String sha256() {
            return RptJson.sha256(this);
        }
    }

    private int page = 2;
    private BigDecimal y = TOP;

    public static AnchorSet layout(UUID revisionId, RenderModel model) {
        return new AnchorLayout().run(revisionId, model);
    }

    private AnchorSet run(UUID revisionId, RenderModel model) {
        List<Anchor> anchors = new ArrayList<>();
        for (Section s : model.sections()) {
            place(new BigDecimal("12"));   // 섹션 제목(앵커 대상 아님 — 블록만)
            for (Block b : s.blocks()) {
                List<Rect> rects = switch (b.kind()) {
                    case "TABLE" -> table(b.rows().size());
                    case "IMAGE" -> List.of(place(new BigDecimal("117")));
                    case "HEADING" -> List.of(place(new BigDecimal("12")));
                    default -> List.of(place(paragraphHeight(b.text())));
                };
                anchors.add(new Anchor(b.blockId(), s.key(), b.kind(), rects.get(0).page(), rects,
                        RptJson.textHash(b.text())));
            }
        }
        return new AnchorSet(revisionId, "ESTIMATED", "mm", page, List.copyOf(anchors));
    }

    static BigDecimal paragraphHeight(String text) {
        int chars = text == null ? 0 : text.codePointCount(0, text.length());
        int lines = Math.max(1, (chars + 39) / 40);
        return BigDecimal.valueOf(lines * 7L + 3L);
    }

    /** 쪽 넘김 없는 덩어리 배치 */
    private Rect place(BigDecimal h) {
        if (y.add(h).compareTo(BOTTOM) > 0 && y.compareTo(TOP) > 0) {
            page++;
            y = TOP;
        }
        Rect r = new Rect(page, LEFT, y, WIDTH, h.min(BOTTOM.subtract(TOP)).setScale(1, RoundingMode.HALF_UP));
        y = y.add(h);
        return r;
    }

    /** 표: 머리 8mm + 행 7mm, 쪽을 넘기면 쪽마다 rect 하나 */
    private List<Rect> table(int rows) {
        List<Rect> out = new ArrayList<>();
        BigDecimal rowH = new BigDecimal("7");
        BigDecimal header = new BigDecimal("8");
        if (y.add(header).add(rowH).compareTo(BOTTOM) > 0) {
            page++;
            y = TOP;
        }
        BigDecimal start = y;
        y = y.add(header);
        for (int i = 0; i < rows; i++) {
            if (y.add(rowH).compareTo(BOTTOM) > 0) {
                out.add(new Rect(page, LEFT, start, WIDTH, y.subtract(start)));
                page++;
                y = TOP.add(header);   // 머리 행 반복(repeatHeader)
                start = TOP;
            }
            y = y.add(rowH);
        }
        out.add(new Rect(page, LEFT, start, WIDTH, y.subtract(start)));
        return out;
    }
}
