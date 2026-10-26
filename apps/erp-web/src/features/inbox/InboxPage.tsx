import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Badge, Banner, Card, ErrorBox, fmtDateTime, Loading } from '@/components/ui';
import { useAuth } from '@/auth/auth';
import { useInbox } from './useInbox';

/** 공통 › 내 할 일 — 여러 모듈에서 "내가 처리할 승인 · 검토" 를 한곳에(읽기 전용, 처리는 각 화면에서) */
export function InboxPage() {
  const { session } = useAuth();
  const qc = useQueryClient();
  const { sources, items, loading } = useInbox();
  if (!session) return <Loading />;
  return (
    <>
      <h1>내 할 일 <Badge tone={items.length ? 'warn' : 'ok'}>{items.length}건</Badge></h1>
      <Banner tone="info">
        여러 모듈에서 지금 <strong>내가 처리할 수 있는</strong> 승인 · 검토만 모았습니다. 내가 만든 견적 · 예산 · 룰셋 · 정정 요청처럼
        본인이 승인할 수 없는 항목은 보이지 않습니다(직무 분리). 항목을 누르면 처리 화면으로 갑니다.
      </Banner>
      <Card
        title="출처"
        actions={<button onClick={() => qc.invalidateQueries({ queryKey: ['inbox'] })}>새로 고침</button>}
      >
        <div className="row" data-testid="inbox-sources">
          {sources.length === 0 && <span className="muted">이 역할에는 수신함 출처가 없습니다.</span>}
          {sources.map(({ source, query }) => (
            <span key={source.id} data-testid={`inbox-src-${source.id}`}>
              <Badge tone={query.error ? 'danger' : query.data?.length ? 'warn' : undefined}>
                {source.label} {query.isLoading ? '…' : query.error ? '오류' : query.data?.length ?? 0}
              </Badge>
            </span>
          ))}
        </div>
        {sources.filter((s) => s.query.error).map((s) => <ErrorBox key={s.source.id} error={s.query.error} />)}
      </Card>
      <Card title="할 일 목록">
        {loading && items.length === 0 && <Loading />}
        <table className="simple" data-testid="inbox-items">
          <thead><tr><th>모듈</th><th>종류</th><th>내용</th><th>요청 시각</th></tr></thead>
          <tbody>
            {items.map((i) => (
              <tr key={i.key} data-testid={`inbox-item-${i.key}`}>
                <td><Badge tone="info">{i.module}</Badge></td>
                <td>{i.kind}</td>
                <td>
                  <Link to={i.link}>{i.title}</Link>
                  {i.detail && <div className="muted" style={{ fontSize: 12 }}>{i.detail}</div>}
                </td>
                <td>{fmtDateTime(i.at)}</td>
              </tr>
            ))}
            {!loading && items.length === 0 && <tr><td colSpan={4} className="muted">처리할 일이 없습니다</td></tr>}
          </tbody>
        </table>
      </Card>
    </>
  );
}

/** 상단 헤더 배지 — 건수만 보여 주고 수신함으로 이동 */
export function InboxBadge() {
  const { count } = useInbox();
  return (
    <Link to="/inbox" className="inbox-link" data-testid="inbox-badge" aria-label={`내 할 일 ${count}건`}>
      내 할 일 <span className={`count ${count ? 'has' : ''}`}>{count}</span>
    </Link>
  );
}
