/** vi.mock 팩토리용 — 앱 모듈(sonner 를 쓰는 코드)을 import 하지 않는다 */
import type { ReactNode } from 'react';

export const toasts = {
  success: [] as string[],
  error: [] as { title: string; description?: string }[],
  warning: [] as string[],
};
export const sonnerModule = {
  toast: {
    success: (m: string) => toasts.success.push(m),
    warning: (m: string) => toasts.warning.push(m),
    error: (title: string, o?: { description?: string }) => toasts.error.push({ title, description: o?.description }),
  },
  Toaster: () => null,
};

function GridMock({ rowData, columnDefs, testId, onRowClicked }: {
  rowData?: unknown[]; columnDefs: Record<string, unknown>[]; testId?: string;
  onRowClicked?: (e: { data: unknown }) => void;
}) {
  return (
    <table data-testid={testId}>
      <tbody>
        {(rowData ?? []).map((data, i) => (
          <tr key={i} onClick={() => onRowClicked?.({ data })}>
            {columnDefs.map((c, j) => {
              const R = c.cellRenderer as ((p: { data: unknown }) => ReactNode) | undefined;
              const value = c.valueGetter
                ? (c.valueGetter as (p: { data: unknown }) => unknown)({ data })
                : (data as Record<string, unknown>)[c.field as string];
              return <td key={j}>{R ? <R data={data} /> : String(value ?? '')}</td>;
            })}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
export const dataGridModule = { DataGrid: GridMock };


/** uPlot(canvas) 대역 — jsdom 에는 canvas 가 없다. 점 개수 · 표시선 · 계열 이름을 표로 남겨 시험에서 읽는다 */
function LineChartMock({ x, series, markers, testId, ariaLabel }: {
  x: string[]; series: { label: string; values: (number | null)[] }[]; markers?: { x: string; label: string }[];
  testId?: string; ariaLabel: string;
}) {
  return (
    <figure data-testid={testId} data-points={x.length} aria-label={ariaLabel}>
      <ul>
        {series.map((s) => <li key={s.label}>{`${s.label}: ${s.values.map((v) => (v == null ? '-' : v)).join(',')}`}</li>)}
      </ul>
      {(markers ?? []).map((m) => <span key={m.x} data-testid="chart-marker">{m.label}</span>)}
    </figure>
  );
}
export const lineChartModule = { LineChart: LineChartMock };
