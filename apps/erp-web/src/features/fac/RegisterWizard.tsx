import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { coreApi } from '@/api/core';
import { facApi, LOOKUP_FAILURE_LABEL, type Building, type CreateBuildingRequest, type LookupView } from '@/api/fac';
import { newIdempotencyKey, ProblemError } from '@/api/http';
import { Badge, Banner, Card, Field } from '@/components/ui';
import { toastError } from '@/app/errors';

interface Candidate { id: string; name: string; nameSimilarity: number | null; matchedBy: string }

/**
 * 시설물 등록 마법사 — ① 주소 조회(도로명주소 → PNU → 건축물대장 표제부, dev 는 WireMock 대역 "합성로 1000~2999")
 * ② 대장 장애 · 주소 없음이면 수기 입력으로 전환 ③ 중복 후보(409 FAC-409-001)면 사유를 남기고 강제 등록.
 */
export function RegisterWizard({ onRegistered }: { onRegistered: (b: Building) => void }) {
  const [mode, setMode] = useState<'lookup' | 'manual'>('lookup');
  const [address, setAddress] = useState('');
  const [lookup, setLookup] = useState<LookupView>();
  const [pk, setPk] = useState('');
  const [owner, setOwner] = useState('');
  const [manual, setManual] = useState({ name: '', roadAddress: '', structureNm: '', floorsAbove: '', floorsBelow: '', approvalDate: '' });
  const [candidates, setCandidates] = useState<Candidate[]>();
  const [forceReason, setForceReason] = useState('');
  // 같은 등록 시도의 재전송은 같은 멱등 키(성공하면 새 키)
  const [idemKey, setIdemKey] = useState(newIdempotencyKey);
  const clients = useQuery({ queryKey: ['clients'], queryFn: coreApi.clients, meta: { silent: true } });

  const doLookup = useMutation({
    mutationFn: () => facApi.lookup(address.trim()),
    onSuccess: (v) => {
      setLookup(v);
      setCandidates(undefined);
      const titles = v.register?.titles ?? [];
      setPk(titles.length === 1 ? titles[0].mgmBldrgstPk : '');
    },
  });

  const body = (force: boolean): CreateBuildingRequest => {
    const base: CreateBuildingRequest = { ownerClientId: owner || undefined };
    if (force) Object.assign(base, { force: true, forceReason });
    if (mode === 'lookup') return { ...base, lookupAddress: address.trim(), mgmBldrgstPk: pk || undefined };
    const num = (s: string) => (s === '' ? undefined : Number(s));
    return {
      ...base, dataSource: 'MANUAL', name: manual.name, roadAddress: manual.roadAddress || undefined,
      structureNm: manual.structureNm || undefined, floorsAbove: num(manual.floorsAbove), floorsBelow: num(manual.floorsBelow),
      approvalDate: manual.approvalDate || undefined,
    };
  };

  const register = useMutation({
    mutationFn: (force: boolean) => facApi.register(body(force), idemKey),
    meta: { silent: true },
    onSuccess: (b) => {
      toast.success(`시설물 등록 — ${b.name}${b.dongName ? ` ${b.dongName}` : ''} (${b.dataSource === 'REGISTER' ? '대장 자동 입력' : '수기'})`);
      setIdemKey(newIdempotencyKey());
      setCandidates(undefined);
      setForceReason('');
      onRegistered(b);
    },
    onError: (e) => {
      if (e instanceof ProblemError && e.code === 'FAC-409-001') {
        setCandidates((e.problem.candidates as Candidate[]) ?? []);
        setIdemKey(newIdempotencyKey());
        return;
      }
      setIdemKey(newIdempotencyKey());
      toastError(e);
    },
  });

  const failure = lookup?.failureReason ?? (lookup?.register && !lookup.register.available ? lookup.register.failureReason : null);
  const titles = lookup?.register?.titles ?? [];
  const canRegister =
    mode === 'lookup' ? !!lookup?.address && !failure && (titles.length <= 1 || !!pk) : manual.name.trim().length > 0;

  const switchToManual = () => {
    setMode('manual');
    setManual((m) => ({ ...m, roadAddress: lookup?.address?.roadAddress ?? address, name: m.name || (lookup?.address?.buildingName ?? '') }));
  };

  return (
    <Card title="시설물 등록" actions={
      <div className="tabs" style={{ marginBottom: 0, border: 'none' }}>
        <button className={mode === 'lookup' ? 'active' : ''} onClick={() => setMode('lookup')}>주소 조회</button>
        <button className={mode === 'manual' ? 'active' : ''} onClick={() => setMode('manual')}>수기 입력</button>
      </div>
    }>
      <div className="form" style={{ marginBottom: 12 }}>
        <Field label="소유 고객사(선택)">
          <select aria-label="소유 고객사" value={owner} onChange={(e) => setOwner(e.target.value)}>
            <option value="">없음</option>
            {(clients.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </Field>
      </div>
      {mode === 'lookup' ? (
        <>
          <div className="form">
            <Field label="도로명주소">
              <input
                aria-label="도로명주소"
                value={address}
                placeholder="예: 합성로 1305 (dev 대역)"
                onChange={(e) => { setAddress(e.target.value); setLookup(undefined); }}
                onKeyDown={(e) => e.key === 'Enter' && address.trim() && doLookup.mutate()}
              />
            </Field>
            <button onClick={() => doLookup.mutate()} disabled={!address.trim() || doLookup.isPending} data-testid="fac-lookup">
              주소 · 대장 조회
            </button>
          </div>
          {lookup && (
            <div style={{ marginTop: 12 }} data-testid="fac-lookup-result">
              {lookup.address && (
                <p>
                  <Badge tone="ok">주소 확인</Badge> {lookup.address.roadAddress}{' '}
                  <span className="muted mono">PNU {lookup.address.pnu.value}</span>
                </p>
              )}
              {failure ? (
                <Banner tone="warn">
                  {LOOKUP_FAILURE_LABEL[failure] ?? failure} — 건축물대장 자동 입력을 쓸 수 없습니다.{' '}
                  <button onClick={switchToManual} data-testid="fac-to-manual">수기 입력으로 계속</button>
                </Banner>
              ) : titles.length > 1 ? (
                <Field label="표제부(동) 선택">
                  <select aria-label="표제부(동)" value={pk} onChange={(e) => setPk(e.target.value)}>
                    <option value="">선택</option>
                    {titles.map((t) => (
                      <option key={t.mgmBldrgstPk} value={t.mgmBldrgstPk}>
                        {t.buildingName} {t.dongName} · 지상 {t.floorsAbove} · {t.grossFloorArea}㎡
                      </option>
                    ))}
                  </select>
                </Field>
              ) : titles.length === 1 ? (
                <p className="muted">
                  표제부: {titles[0].buildingName} · {titles[0].mainPurposeNm} · {titles[0].structureNm} · 지상 {titles[0].floorsAbove} / 지하{' '}
                  {titles[0].floorsBelow} · 사용승인 {titles[0].approvalDate}
                </p>
              ) : null}
            </div>
          )}
        </>
      ) : (
        <div className="form">
          <Field label="건물명">
            <input aria-label="건물명" value={manual.name} onChange={(e) => setManual({ ...manual, name: e.target.value })} />
          </Field>
          <Field label="도로명주소(수기)">
            <input aria-label="도로명주소(수기)" value={manual.roadAddress} onChange={(e) => setManual({ ...manual, roadAddress: e.target.value })} />
          </Field>
          <Field label="구조">
            <input aria-label="구조" value={manual.structureNm} placeholder="철근콘크리트구조" onChange={(e) => setManual({ ...manual, structureNm: e.target.value })} />
          </Field>
          <Field label="지상 층수">
            <input aria-label="지상 층수" type="number" min={0} max={200} value={manual.floorsAbove} onChange={(e) => setManual({ ...manual, floorsAbove: e.target.value })} />
          </Field>
          <Field label="지하 층수">
            <input aria-label="지하 층수" type="number" min={0} max={30} value={manual.floorsBelow} onChange={(e) => setManual({ ...manual, floorsBelow: e.target.value })} />
          </Field>
          <Field label="사용승인일">
            <input aria-label="사용승인일" type="date" value={manual.approvalDate} onChange={(e) => setManual({ ...manual, approvalDate: e.target.value })} />
          </Field>
        </div>
      )}

      {candidates && (
        <Banner tone="warn" testId="fac-duplicates">
          <strong>중복 시설물 후보 {candidates.length}건</strong> (FAC-409-001)
          <ul>
            {candidates.map((c) => (
              <li key={c.id}>
                {c.name} — {c.matchedBy === 'PNU_DONG' ? 'PNU · 동 완전 일치(강제 등록 불가)' : `일치 근거 ${c.matchedBy}`}
                {c.nameSimilarity !== null && ` · 이름 유사도 ${Math.round(c.nameSimilarity * 100)}%`}
              </li>
            ))}
          </ul>
          {!candidates.some((c) => c.matchedBy === 'PNU_DONG') && (
            <div className="form">
              <Field label="강제 등록 사유">
                <input aria-label="강제 등록 사유" value={forceReason} onChange={(e) => setForceReason(e.target.value)} />
              </Field>
              <button className="danger" disabled={forceReason.trim().length < 5 || register.isPending} onClick={() => register.mutate(true)}>
                사유 남기고 등록
              </button>
            </div>
          )}
        </Banner>
      )}

      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={!canRegister || register.isPending} onClick={() => register.mutate(false)} data-testid="fac-register">
          등록
        </button>
        <span className="muted">
          {mode === 'lookup' ? '대장 조회 성공 = 대장 확인(VERIFIED). 층수만큼 층 노드가 자동 생성됩니다.' : '수기 등록은 초안(DRAFT)으로 남고 감사 로그에 사유가 기록됩니다.'}
        </span>
      </div>
    </Card>
  );
}
