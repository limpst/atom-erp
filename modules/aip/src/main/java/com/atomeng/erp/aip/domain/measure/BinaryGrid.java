package com.atomeng.erp.aip.domain.measure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 이진 격자(마스크 · 중심선). 행 우선, 범위 밖은 0 으로 본다. 불변처럼 다루되 내부 복사본으로만 수정한다. */
public final class BinaryGrid {

    private final int height;
    private final int width;
    private final boolean[] cells;

    public BinaryGrid(int height, int width) {
        if (height <= 0 || width <= 0) {
            throw new IllegalArgumentException("격자 크기는 양수여야 합니다");
        }
        this.height = height;
        this.width = width;
        this.cells = new boolean[height * width];
    }

    private BinaryGrid(int height, int width, boolean[] cells) {
        this.height = height;
        this.width = width;
        this.cells = cells;
    }

    /** 행별 구간 [row, colStart, colEndExclusive] 목록으로 만든다(골든 픽스처 형식) */
    public static BinaryGrid fromRuns(int height, int width, List<int[]> runs) {
        BinaryGrid g = new BinaryGrid(height, width);
        for (int[] r : runs) {
            for (int c = r[1]; c < r[2]; c++) {
                g.set(r[0], c, true);
            }
        }
        return g;
    }

    public static BinaryGrid fromPoints(int height, int width, List<int[]> points) {
        BinaryGrid g = new BinaryGrid(height, width);
        for (int[] p : points) {
            g.set(p[0], p[1], true);
        }
        return g;
    }

    public int height() {
        return height;
    }

    public int width() {
        return width;
    }

    public boolean get(int r, int c) {
        return r >= 0 && r < height && c >= 0 && c < width && cells[r * width + c];
    }

    public void set(int r, int c, boolean v) {
        cells[r * width + c] = v;
    }

    public int count() {
        int n = 0;
        for (boolean b : cells) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    public BinaryGrid copy() {
        return new BinaryGrid(height, width, Arrays.copyOf(cells, cells.length));
    }

    /** 두 격자의 교집합(크기 같아야 함) */
    public BinaryGrid and(BinaryGrid o) {
        if (o.height != height || o.width != width) {
            throw new IllegalArgumentException("격자 크기 불일치");
        }
        boolean[] out = new boolean[cells.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = cells[i] && o.cells[i];
        }
        return new BinaryGrid(height, width, out);
    }

    /** 켜진 칸 좌표(행 우선) */
    public List<int[]> points() {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (cells[r * width + c]) {
                    out.add(new int[] {r, c});
                }
            }
        }
        return out;
    }
}
