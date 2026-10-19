import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http as mswHttp, HttpResponse } from 'msw';
import { devUserId } from '@/auth/devUsers';
import { ADMIN_DEVICE_ID, allowedResolutions } from '@/api/fld';
import { record, renderAs, seen, toasts, useMswServer } from '@/test/mocks';
import { FldAdminPage } from './FldAdminPage';

vi.mock('sonner', async () => (await import('@/test/mockModules')).sonnerModule);

const PM = devUserId(4);
const ENG = devUserId(5);
const round = {
  id: 'r1', projectId: 'p1', buildingId: 'b1', roundNo: 1, surveyType: 'PRECISE_INSPECTION', evidenceMode: false, status: 'IN_PROGRESS',
  completedAt: null, lastSeq: 17,
  zones: [
    { id: 'z3', roundId: 'r1', zoneName: '1F-계단실', assigneeId: ENG, floorNodeId: 'f1', status: 'LOCKED', defectCount: 1 },
    { id: 'z1', roundId: 'r1', zoneName: '1F-전체', assigneeId: ENG, floorNodeId: 'f1', status: 'IN_PROGRESS', defectCount: 1 },
    { id: 'z2', roundId: 'r1', zoneName: '2F-전체', assigneeId: ENG, floorNodeId: 'f2', status: 'IN_PROGRESS', defectCount: 2 },
  ],
};
let resolved = false;
const conflict = {
  id: 'cf1', roundId: 'r1', zoneId: 'z1', defectId: 'd1', field: 'attrs.width_mm', strategy: 'MANUAL', localEventType: 'DefectMeasured',
  localValue: 0.3, localPayload: { item: 'width_mm', value: 0.3 }, localHlc: '0001792396430903:0016:db0b',
  localDeviceId: '00000000-0000-7000-8000-00000000db0b', localActorId: PM, serverSeq: 16, serverValue: 0.25,
  serverHlc: '0001792396430895:0015:da0a', serverDeviceId: '00000000-0000-7000-8000-00000000da0a',
  status: 'OPEN', resolution: null, resolvedValue: null, resolvedBy: null, resolvedAt: null,
};

useMswServer(
  mswHttp.get('*/api/v1/core/projects', () => HttpResponse.json([{ id: 'p1', projectNo: 'PRJ-2026-0001', name: '(합성) 한빛타워' }])),
  mswHttp.get('*/api/v1/hrq/employees', () => HttpResponse.json([
    { id: ENG, empNo: 'D-005', name: '정참여', status: 'ACTIVE' }, { id: PM, empNo: 'D-004', name: '최책임', status: 'ACTIVE' },
  ])),
  mswHttp.get('*/api/v1/fld/rounds', () => HttpResponse.json([round])),
  mswHttp.get('*/api/v1/fld/zones/:id/lock-check', ({ params }) => HttpResponse.json(
    params.id === 'z1'
      ? { zoneId: 'z1', ready: resolved, issues: resolved ? [] : [{ kind: 'OPEN_CONFLICT', defectId: 'd1', defectCode: 'CRK-0001', message: '미해결 동기화 충돌: attrs.width_mm', conflictId: 'cf1' }] }
      : { zoneId: 'z2', ready: false, issues: [
        { kind: 'REQUIRED_MISSING', defectId: 'd2', defectCode: 'CRK-0002', message: '필수 측정 누락: length_m' },
        { kind: 'PHOTO_REJECTED', defectId: 'd3', defectCode: 'CRK-0003', message: '사진 해시 불일치', photoId: 'ph3' },
      ] },
  )),
  mswHttp.get('*/api/v1/fld/sync/conflicts', () => HttpResponse.json(resolved ? [] : [conflict])),
  mswHttp.post('*/api/v1/fld/sync/conflicts/:id/resolve', async ({ request }) => {
    await record(request);
    resolved = true;
    const b = (await request.json()) as { resolution: string };
    return HttpResponse.json({ ...conflict, status: 'RESOLVED', resolution: b.resolution, resolvedValue: 0.28 });
  }),
  mswHttp.get('*/api/v1/fld/rounds/:id/photos', () => HttpResponse.json([
    { photoId: 'ph4', defectId: 'd4', defectCode: 'CRK-0004', kind: 'NEAR', uploadState: 'VERIFIED', deviceSha256: 'a'.repeat(64), serverSha256: 'a'.repeat(64), attachmentId: 'at', rejectReason: null, rejectCount: 0, sizeBytes: 5100, verifiedAt: '2026-10-19T07:00:00Z', invalidated: false },
    { photoId: 'ph3', defectId: 'd3', defectCode: 'CRK-0003', kind: 'NEAR', uploadState: 'REJECTED', deviceSha256: 'b'.repeat(64), serverSha256: null, attachmentId: null, rejectReason: 'HASH_MISMATCH', rejectCount: 1, sizeBytes: 5100, verifiedAt: null, invalidated: false },
  ])),
  mswHttp.get('*/api/v1/fld/photos/:id/verify', ({ params }) =>
    HttpResponse.json({ photoId: params.id, uploadState: 'VERIFIED', deviceSha256: 'a', attachmentId: 'at', serverSha256: 'a', result: 'MATCH' })),
);

describe('FLD 관리', () => {
  it('삭제 우선(REMOVE_WINS) 충돌은 서버 값 유지만', () => {
    expect(allowedResolutions({ strategy: 'REMOVE_WINS' })).toEqual(['KEEP_SERVER']);
    expect(allowedResolutions({ strategy: 'MANUAL' })).toHaveLength(3);
  });

  it('구역 잠금 상태 · 막는 사유 · 사진 검증 상태', async () => {
    const user = userEvent.setup();
    resolved = false;
    renderAs(PM, ['PM'], <FldAdminPage />);
    const zones = await screen.findByTestId('fld-zones');
    expect(await within(zones).findByText('필수 측정 누락')).toBeInTheDocument();
    expect(within(zones).getByText('사진 검증 실패')).toBeInTheDocument();
    expect(within(zones).getByText(/잠김 — 수정은 PM 사유 필요/)).toBeInTheDocument();
    expect(within(zones).getByRole('button', { name: '잠금 2F-전체' })).toBeDisabled();
    const photos = screen.getByTestId('fld-photos');
    expect(await within(photos).findByText('검증 실패(재업로드)')).toHaveClass('danger');
    expect(within(photos).getByText('해시 불일치(전송 중 손상)')).toBeInTheDocument();
    await user.click(within(photos).getByRole('button', { name: '검증 CRK-0004' }));
    expect(await within(photos).findByText('MATCH')).toHaveClass('ok');
  });

  it('충돌 해결(병합 값) → opId · deviceId 전송, 해결 후 구역 잠금 가능', async () => {
    const user = userEvent.setup();
    resolved = false;
    renderAs(PM, ['PM'], <FldAdminPage />);
    const table = await screen.findByTestId('fld-conflicts');
    await user.click(await within(table).findByRole('button', { name: '해결 1F-전체 attrs.width_mm' }));
    const dialog = screen.getByRole('dialog', { name: '동기화 충돌 해결' });
    expect(within(dialog).getByTestId('fld-server-value')).toHaveTextContent('0.25');
    expect(within(dialog).getByTestId('fld-client-value')).toHaveTextContent('0.3');
    await user.click(within(dialog).getByLabelText('직접 값 입력(병합)'));
    await user.clear(within(dialog).getByLabelText('병합 값'));
    await user.type(within(dialog).getByLabelText('병합 값'), '0.28');
    await user.click(within(dialog).getByTestId('fld-resolve-confirm'));
    await waitFor(() => expect(toasts.success[0]).toContain('충돌 해결'));
    expect(seen[0].url).toBe('/api/v1/fld/sync/conflicts/cf1/resolve');
    expect(seen[0].body).toMatchObject({ resolution: 'MERGE', deviceId: ADMIN_DEVICE_ID, merged: { value: 0.28 } });
    expect((seen[0].body as { opId: string }).opId).toMatch(/^[0-9a-f-]{36}$/);
    await waitFor(() => expect(within(screen.getByTestId('fld-zones')).getByRole('button', { name: '잠금 1F-전체' })).toBeEnabled());
    expect(within(screen.getByTestId('fld-conflicts')).getByText('미해결 충돌 없음')).toBeInTheDocument();
  });

  it('서버 값 유지는 opId 없이', async () => {
    const user = userEvent.setup();
    resolved = false;
    renderAs(PM, ['PM'], <FldAdminPage />);
    await user.click(await within(await screen.findByTestId('fld-conflicts')).findByRole('button', { name: /해결/ }));
    await user.click(screen.getByTestId('fld-resolve-confirm'));
    await waitFor(() => expect(seen[0]?.body).toEqual({ resolution: 'KEEP_SERVER' }));
  });
});
