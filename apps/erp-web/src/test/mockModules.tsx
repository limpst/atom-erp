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

