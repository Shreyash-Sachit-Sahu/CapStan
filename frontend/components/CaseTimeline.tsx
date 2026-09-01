'use client';

import { useState } from 'react';
import { human, ist } from '@/lib/api';
import WhyPanel from './WhyPanel';

export type Event = {
  id: number; seq: number; eventType: string; actor: string;
  payload: string; occurredAt: string; prevHash: string; hash: string;
};

/**
 * Events where Capstan declined to act. These are the point of the view: a
 * timeline that shows only what happened cannot demonstrate boundedness, and a
 * held action is the system working rather than an error — so they are greyed
 * and struck, never coloured as failures.
 */
const HELD = new Set(['COMMS_SUPPRESSED', 'GUARDRAIL_BLOCKED', 'DEBIT_BLOCKED', 'INTERVENTION_CANCELLED']);

function describe(type: string, p: Record<string, any>): { what: string; why?: string } {
  switch (type) {
    case 'CASE_OPENED': return { what: 'Case opened', why: `${p.rawErrorReason ?? ''}` };
    case 'DIAGNOSIS_ATTEMPTED': return { what: `Diagnosis via ${human(p.method)}`, why: `${p.latencyMs}ms` };
    case 'DIAGNOSIS_RESOLVED':
      return { what: `Diagnosed ${human(p.cause)}`, why: `confidence ${p.confidence}. ${p.evidence ?? ''}` };
    case 'DECISION_MADE': return { what: 'Decision', why: p.humanReadable };
    case 'INTERVENTION_SCHEDULED':
      return { what: `Scheduled ${human(p.kind)}`, why: `attempt ${p.attemptNo} · ${p.rationale ?? ''}` };
    case 'GUARDRAIL_BLOCKED':
      return { what: `Held ${human(p.proposedAction)}`, why: `${p.guardrailId} ${p.guardrailName}: ${p.reason}` };
    case 'COMMS_SUPPRESSED':
      return { what: 'Did not message the customer', why: `${p.guardrailId} ${p.guardrailName}: ${p.reason}` };
    case 'DEBIT_BLOCKED': return { what: 'Did not debit', why: p.reason };
    case 'DEBIT_INITIATED':
      return { what: `Debit attempted on ${p.rail}`, why: `sequence ${p.debitSeq} · key ${String(p.idempotencyKey).slice(0, 14)}…` };
    case 'GATEWAY_RESPONSE':
      return { what: p.state === 'SUCCEEDED' ? 'Gateway: succeeded' : 'Gateway: declined', why: p.reason ?? p.gatewayRef };
    case 'ATTEMPT_UNKNOWN':
      return { what: 'Gateway did not confirm the outcome', why: `${p.reason}. Recorded UNKNOWN, no further debit until reconciled` };
    case 'RECONCILE_ATTEMPTED':
      return { what: 'Asked the gateway what happened', why: `resolved: ${p.resolvedState}` };
    case 'COMMS_SENT': return { what: `Sent ${human(p.kind)}`, why: `${p.locale} · ${p.fromTemplate ? 'template' : 'generated'} copy` };
    case 'INTERVENTION_CANCELLED': return { what: `Cancelled ${human(p.kind)}`, why: p.reason };
    case 'CASE_TERMINATED': return { what: `Case closed: ${human(p.status)}`, why: p.terminalReason };
    default: return { what: human(type) };
  }
}

export default function CaseTimeline({ events, decisions }: { events: Event[]; decisions: any[] }) {
  const [selected, setSelected] = useState(
    events.findIndex((e) => e.eventType === 'DECISION_MADE') >= 0
      ? events.findIndex((e) => e.eventType === 'DECISION_MADE') : 0);

  const active = events[selected];
  const activeDecision =
    decisions.find((d) => d.decision?.decisionId === parsed(active)?.decisionId) ?? decisions[0];

  return (
    <div className="grid2" style={{ alignItems: 'start' }}>
      <div className="panel">
        <h2>Timeline · Asia/Kolkata</h2>
        <ul className="tl">
          {events.map((e, i) => {
            const p = parsed(e) ?? {};
            const { what, why } = describe(e.eventType, p);
            const held = HELD.has(e.eventType);
            return (
              <li key={e.id} className={held ? 'held' : undefined}>
                <button aria-current={i === selected} onClick={() => setSelected(i)}>
                  <span className="seq">{String(e.seq).padStart(2, '0')}</span>
                  <span className="when">{ist(e.occurredAt)}</span>
                  <span>
                    <span className="what">
                      {held && <span className="tag held">held</span>}
                      {what}
                    </span>
                    {why && <span className="why" style={{ display: 'block' }}>{why}</span>}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </div>

      <WhyPanel event={active} decision={activeDecision} />
    </div>
  );
}

function parsed(e: Event | undefined): Record<string, any> | null {
  if (!e) return null;
  try { return JSON.parse(e.payload); } catch { return null; }
}
