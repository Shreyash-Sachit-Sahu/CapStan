import CaseTimeline, { type Event } from '@/components/CaseTimeline';
import AuditChain from '@/components/AuditChain';
import { get } from '@/lib/api';

export const dynamic = 'force-dynamic';

type Trail = {
  caseId: string;
  events: Event[];
  narrative: { at: string; text: string }[];
  decisions: any[];
  verification: { valid: boolean; events: number };
};

export default async function CaseView({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const trail = await get<Trail>(`/api/cases/${id}/trail`);

  if (!trail || trail.events.length === 0) {
    return (
      <section>
        <h1>No trail for this case</h1>
        <p className="lede">
          Either the id is wrong or no measurement has been run since the ledger
          was last reset.
        </p>
        <div className="empty" style={{ marginTop: 20 }}>
          <code>{`curl -X POST 'localhost:8080/api/backtest/run?batch=holdout&inject=timeout_rate:0.05'`}</code>
        </div>
      </section>
    );
  }

  return (
    <>
      <section>
        <h1>Case {id.slice(0, 8)}</h1>
        <p className="lede">
          {trail.narrative.length} events, chain{' '}
          {trail.verification.valid ? 'intact' : 'BROKEN'}. Held actions are shown
          struck through — what the system declined to do is the evidence that it
          is bounded.
        </p>
      </section>

      <section>
        <CaseTimeline events={trail.events} decisions={trail.decisions ?? []} />
      </section>

      <section>
        <AuditChain caseId={id} events={trail.events} />
      </section>
    </>
  );
}
