import { useEffect, useRef } from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import { useThemeContext } from '@/app/ThemeContext';

/**
 * 가벼운 선 그래프(uPlot, MIT · 약 50KB) — 앱 테마(light/dark) 색을 CSS 변수에서 읽는다.
 * x 는 날짜 'YYYY-MM-DD'(일 단위). 세로 표시선(markers)으로 "최초 미달일" 같은 날짜를 강조한다.
 * 시험(jsdom, canvas 없음)에서는 vi.mock 으로 표 대역을 쓴다(src/test/mockModules.tsx).
 */
export interface ChartSeries {
  label: string;
  values: (number | null)[];
  /** CSS 변수 이름(--primary 등) 또는 색 */
  color?: string;
  dash?: boolean;
  /** true 면 계단식(등록요건 미달 인원처럼 변화점 사이가 일정한 값) */
  stepped?: boolean;
  /** 오른쪽 축(지수 · 비율 등 단위가 다른 값) */
  rightAxis?: boolean;
}
export interface ChartMarker { x: string; label: string; color?: string }
export interface LineChartProps {
  x: string[];
  series: ChartSeries[];
  height?: number;
  markers?: ChartMarker[];
  yLabel?: string;
  y2Label?: string;
  testId?: string;
  ariaLabel: string;
  valueFormat?: (v: number) => string;
}

const toSec = (d: string) => Date.parse(`${d}T00:00:00+09:00`) / 1000;

function cssVar(el: Element, v: string | undefined, fallback: string) {
  if (!v) return fallback;
  if (!v.startsWith('--')) return v;
  return getComputedStyle(el).getPropertyValue(v).trim() || fallback;
}

export function LineChart({ x, series, height = 260, markers = [], yLabel, y2Label, testId, ariaLabel, valueFormat }: LineChartProps) {
  const host = useRef<HTMLDivElement>(null);
  const { theme } = useThemeContext();

  useEffect(() => {
    const el = host.current;
    if (!el || x.length === 0) return;
    let plot: uPlot | undefined;
    let ro: ResizeObserver | undefined;
    // 테마 전환 직후에는 :root[data-theme] 가 부모 effect 에서 바뀌므로 한 프레임 뒤에 CSS 변수를 읽는다
    const raf = requestAnimationFrame(() => {
      const text = cssVar(el, '--text-2', '#5a6578');
      const grid = cssVar(el, '--border', '#d9dee7');
      const xs = x.map(toSec);
      const fmt = valueFormat ?? ((v: number) => v.toLocaleString('ko-KR'));
      const hasRight = series.some((s) => s.rightAxis);
      const markerPlugin: uPlot.Plugin = {
        hooks: {
          draw: [
            (u) => {
              const ctx = u.ctx;
              for (const m of markers) {
                const px = u.valToPos(toSec(m.x), 'x', true);
                if (!Number.isFinite(px)) continue;
                ctx.save();
                ctx.strokeStyle = cssVar(el, m.color ?? '--danger', '#c0392b');
                ctx.fillStyle = ctx.strokeStyle;
                ctx.setLineDash([6, 4]);
                ctx.lineWidth = 2 * uPlot.pxRatio;
                ctx.beginPath();
                ctx.moveTo(px, u.bbox.top);
                ctx.lineTo(px, u.bbox.top + u.bbox.height);
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.font = `${12 * uPlot.pxRatio}px sans-serif`;
                ctx.textAlign = 'left';
                ctx.textBaseline = 'top';
                ctx.fillText(m.label, px + 6 * uPlot.pxRatio, u.bbox.top + 4 * uPlot.pxRatio);
                ctx.restore();
              }
            },
          ],
        },
      };
      const opts: uPlot.Options = {
        width: Math.max(320, el.clientWidth),
        height,
        plugins: [markerPlugin],
        scales: {
          x: { time: true },
          // 0 을 포함해 위로 10% 여유(인원 · 금액이 0 근처에서 확대돼 보이지 않게)
          y: { range: (_u, min, max) => [Math.min(0, min ?? 0), Math.max(1, (max ?? 0) * 1.1)] },
          ...(hasRight ? { y2: { range: (_u: uPlot, min: number, max: number) => [Math.min(0, min ?? 0), Math.max(1, (max ?? 0) * 1.1)] } } : {}),
        },
        axes: [
          { stroke: text, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid } },
          { stroke: text, grid: { stroke: grid, width: 1 }, ticks: { stroke: grid }, label: yLabel, size: 70,
            values: (_u, vals) => vals.map((v) => (v == null ? '' : fmt(v))) },
          ...(hasRight ? [{ scale: 'y2', side: 1 as const, stroke: text, grid: { show: false }, label: y2Label, size: 60 }] : []),
        ],
        series: [
          { label: '날짜', value: (_u, v) => (v == null ? '—' : new Date(v * 1000).toLocaleDateString('ko-KR')) },
          ...series.map((s) => ({
            label: s.label,
            stroke: cssVar(el, s.color ?? '--primary', '#1f5fbf'),
            width: 2,
            dash: s.dash ? [6, 4] : undefined,
            scale: s.rightAxis ? 'y2' : 'y',
            points: { show: x.length < 40 },
            paths: s.stepped ? uPlot.paths.stepped!({ align: 1 }) : undefined,
            value: (_u: uPlot, v: number | null) => (v == null ? '—' : s.rightAxis ? v.toFixed(3) : fmt(v)),
          })),
        ],
        legend: { show: true },
      };
      const data = [xs, ...series.map((s) => s.values)] as uPlot.AlignedData;
      const p = new uPlot(opts, data, el);
      plot = p;
      ro = typeof ResizeObserver !== 'undefined'
        ? new ResizeObserver(() => p.setSize({ width: Math.max(320, el.clientWidth), height }))
        : undefined;
      ro?.observe(el);
    });
    return () => {
      cancelAnimationFrame(raf);
      ro?.disconnect();
      plot?.destroy();
    };
  }, [x, series, height, markers, yLabel, y2Label, theme, valueFormat]);

  return (
    <figure style={{ margin: 0 }} data-testid={testId} data-points={x.length} aria-label={ariaLabel}>
      {x.length === 0 ? <div className="empty">표시할 데이터가 없습니다</div> : <div ref={host} className="uplot-host" />}
    </figure>
  );
}
