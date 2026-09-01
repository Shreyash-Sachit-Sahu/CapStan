'use client';

import { human, ist, rupees } from '@/lib/api';
import type { Event } from './CaseTimeline';

/**
 * The DecisionRecord, rendered whole.
 *
 * Every guardrail appears with its result, including the ones that allowed and
 * the ones that did not apply. Showing only the blocks would be advocacy; the
 * claim is that eleven-plus bounds were evaluated and most of them said yes,
 * which is only checkable if they are all here.
 */
export default function WhyPanel({ event, decision }: { event?: Event; decision?: any }) {
  const d = decision?.decision;
  if (!d) {
    return (
      <div className="panel">
        <h2>Why</h2>
        <p className="dim">No decision record attached to this case.</p>
      </div>
    );
  }

  const verdict = (r: string) =>
    r === 'BLOCK' ? 'halt' : r === 'DEFER' ? 'slip' : r === 'N/A' ? 'dim' : 'haul';

  return (
    <div className="panel">
      <h2>Why <span className="dim">attempt {decision.attemptNo}</span></h2>

      <p style={{ fontSize: 15, lineHeight: 1.55, margin: '0 0 18px' }}>{d.humanReadable}</p>

      <table style={{ marginBottom: 20 }}>
        <tbody>
          <tr><td className="dim">Diagnosis</td>
            <td className="num">{human(d.diagnosis?.cause)}</td></tr>
          <tr><td className="dim">Confidence · method</td>
            <td className="num">{d.diagnosis?.confidence} · {human(d.diagnosis?.method)}</td></tr>
          {d.diagnosis?.evidence && (
            <tr><td className="dim">Evidence</td><td className="num wrap">{d.diagnosis.evidence}</td></tr>)}
          <tr><td className="dim">Policy · ladder position</td>
            <td className="num">{d.policyName} · {d.ladderPosition}</td></tr>
          <tr><td className="dim">Proposed → final</td>
            <td className="num">{human(d.proposedAction)} → <span className="haul">{human(d.finalAction)}</span></td></tr>
          <tr><td className="dim">Amount at risk</td>
            <td className="num slip">{rupees(d.amountAtRiskPaise)}</td></tr>
          {d.scheduledFor && (
            <tr><td className="dim">Scheduled for</td><td className="num">{ist(d.scheduledFor)}</td></tr>)}
          {d.terminalStatus && (
            <tr><td className="dim">Terminal</td>
              <td className="num wrap">{human(d.terminalStatus)}: {d.terminalReason}</td></tr>)}
        </tbody>
      </table>

      {/* Nested under "Why", so it is h3. It was an h2, which made a panel
          title and its own subsection rank identically. */}
      <h3 style={{ marginBottom: 'var(--s-2)' }}>Guardrails</h3>
      <table>
        <thead>
          <tr><th scope="col" style={{ width: 40 }}>ID</th><th scope="col">Bound</th><th scope="col" className="num">Result</th></tr>
        </thead>
        <tbody>
          {(d.guardrails ?? []).map((g: any) => (
            <tr key={g.id}>
              <td className="hash">{g.id}</td>
              <td>
                {g.name}
                {g.detail && <div className="foot" style={{ margin: 0 }}>{g.detail}</div>}
              </td>
              <td className={`num ${verdict(g.result)}`}>{g.result}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {d.stopConditions?.length > 0 && (
        <p className="foot">Stop conditions: {d.stopConditions.join(', ')}</p>
      )}
    </div>
  );
}
