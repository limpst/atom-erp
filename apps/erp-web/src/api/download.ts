import { request } from './http';

/**
 * 파일 다운로드 공통 — 서버 파일(오류 리포트 CSV · HWPX · DXF)은 인증 헤더가 필요해 <a href> 대신 fetch → Blob → 저장.
 * 파일명은 Content-Disposition(RFC 6266 filename* 우선)에서, 없으면 fallback.
 */
export interface Downloaded { blob: Blob; filename: string; size: number }

export function filenameFrom(disposition: string | null | undefined, fallback: string): string {
  if (!disposition) return fallback;
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''));
    } catch {
      /* 잘못된 인코딩 → 아래 */
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/.exec(disposition);
  return plain ? plain[1].trim() : fallback;
}

export async function fetchFile(path: string, fallback: string, accept = '*/*'): Promise<Downloaded> {
  const r = await request<Blob>('GET', path, { accept: `${accept}, application/problem+json`, asBlob: true });
  const blob = r.data;
  return { blob, filename: filenameFrom(r.headers.get('Content-Disposition'), fallback), size: blob.size };
}

/** 브라우저 저장(테스트 · jsdom 에서는 URL.createObjectURL 이 없으면 건너뜀) */
export function saveBlob(blob: Blob, filename: string) {
  if (typeof URL.createObjectURL !== 'function') return;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function downloadFile(path: string, fallback: string, accept?: string): Promise<Downloaded> {
  const f = await fetchFile(path, fallback, accept);
  saveBlob(f.blob, f.filename);
  return f;
}

/**
 * CSV 셀 — 수식 주입(CSV/Formula injection) 무력화: = + - @ 탭 CR 로 시작하면 앞에 ' 를 붙인다(서버 FCD 오류 리포트와 같은 규칙,
 * SEC-FCD-009). 쉼표 · 따옴표 · 줄바꿈이 있으면 RFC 4180 인용.
 */
export function csvCell(v: unknown): string {
  if (v === null || v === undefined) return '';
  let s = String(v);
  if (s.length > 0 && '=+-@\t\r'.includes(s[0])) s = `'${s}`;
  if (/[",\r\n]/.test(s)) s = `"${s.replace(/"/g, '""')}"`;
  return s;
}

/** BOM 포함 UTF-8 CSV(Excel 한글) — 화면 목록 내보내기용 */
export function toSafeCsv(header: string[], rows: unknown[][]): string {
  return '﻿' + [header, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
}

export function downloadCsv(filename: string, header: string[], rows: unknown[][]) {
  saveBlob(new Blob([toSafeCsv(header, rows)], { type: 'text/csv;charset=utf-8' }), filename);
}
