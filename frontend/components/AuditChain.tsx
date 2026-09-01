'use client';

import { useState } from 'react';
import type { Event } from './CaseTimeline';

/**
 * The hash chain, with a verify button that recomputes it server-side.
 *
 * A narrative nobody checked is just a story, so verification is offered on the
 * same screen as the story rather than in a separate admin corner.
 */
export default function AuditChain({ caseId, events }: { caseId: string; events: Event[] }) {
  const [state, setState] = useState<'idle' | 'checking' | 'ok' | 'broken'>('idle');
  const [detail, setDetail] = useState<string>('');

  async function verify() {
    setState('checking');
    try {
      const res = await fetch(`/api/ledger/verify/${caseId}`, { cache: 'no-store' });
      const body = await res.json();
      if (body.valid) {
        setState('ok');
        setDetail(`${body.events} links intact`);
      } else {
        setState('broken');
        setDetail(`${body.firstBreak?.kind} at sequence ${body.firstBreak?.seq}`);
      }
    } catch {
      setState('broken');
      setDetail('verification endpoint unreachable');
    }
  }

  return (
    <div className="panel">
      <h2>Audit chain</h2>
      <table>
        <thead>
          <tr>
            <th scope="col" style={{ width: 40 }}>Seq</th><th scope="col">Event</th><th scope="col">Actor</th>
            <th scope="col" className="num">prev</th><th scope="col" className="num">hash</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => (
            <tr key={e.id}>
              <td className="hash">{String(e.seq).padStart(2, '0')}</td>
              <td>{e.eventType}</td>
              <td className="dim">{e.actor}</td>
              <td className="num hash">{e.prevHash.slice(0, 8)}</td>
              <td className="num hash">{e.hash.slice(0, 8)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ marginTop: 16, display: 'flex', alignItems: 'center' }}>
        <button className="btn" onClick={verify} disabled={state === 'checking'}>
          {state === 'checking' ? 'Verifying…' : 'Verify chain'}
        </button>
        {state === 'ok' && <span className="verdict haul">Chain intact. {detail}</span>}
        {state === 'broken' && <span className="verdict halt">Chain broken. {detail}</span>}
      </div>
      <p className="foot">
        audit_event is append-only by database trigger. Editing a row requires
        DDL privilege to switch that trigger off, and the chain still catches it.
      </p>
    </div>
  );
}
