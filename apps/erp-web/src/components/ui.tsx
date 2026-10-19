import type { ReactNode } from 'react';
import { describeError } from '@/app/errors';

export function Badge({ children, tone }: { children: ReactNode; tone?: 'ok' | 'warn' | 'danger' | 'info' }) {
  return <span className={`badge ${tone ?? ''}`}>{children}</span>;
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
          {title ? <h3 style={{ margin: 0 }}>{title}</h3> : <span />}
          {actions && <div className="row">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="field">
      {label}
      {children}
    </label>
  );
}

export function Kpi({ label, value, tone }: { label: string; value: ReactNode; tone?: 'ok' | 'warn' | 'danger' }) {
  return (
    <div className="kpi">
      <div className="label">{label}</div>
      <div className="value" style={tone ? { color: `var(--${tone})` } : undefined}>
        {value ?? '—'}
      </div>
    </div>
  );
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null;
  const d = describeError(error);
  return (
    <div className="error-box" role="alert">
      <strong>{d.title}</strong>
      {d.description && <div style={{ whiteSpace: 'pre-wrap', marginTop: 4 }}>{d.description}</div>}
    </div>
  );
}

export function Loading() {
  return <div className="empty">불러오는 중…</div>;
}

export function Steps({ steps, current }: { steps: { key: string; label: string }[]; current?: string }) {
  const idx = steps.findIndex((s) => s.key === current);
  return (
    <div className="steps">
      {steps.map((s, i) => (
        <span key={s.key} className={`step ${i < idx ? 'done' : i === idx ? 'current' : ''}`}>
          {s.label}
        </span>
      ))}
    </div>
  );
}

const won = new Intl.NumberFormat('ko-KR');
export function fmtMoney(v: number | string | null | undefined) {
  if (v === null || v === undefined || v === '') return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  return Number.isFinite(n) ? `${won.format(n)}원` : String(v);
}
export function fmtNum(v: number | string | null | undefined, digits = 2) {
  if (v === null || v === undefined || v === '') return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  return Number.isFinite(n) ? n.toLocaleString('ko-KR', { maximumFractionDigits: digits }) : String(v);
}
export function fmtDateTime(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : d.toLocaleString('ko-KR', { hour12: false });
}
