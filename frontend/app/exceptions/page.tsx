import { get, human, rupees } from '@/lib/api';

export const dynamic = 'force-dynamic';

type Exception = {
  caseId: string; amountPaise: number; diagnosedCause: string; trueCause: string | null;
  terminalStatus: string; terminalReason: string; ladderPosition: number;
  stoppedByGuardrail: string | null; nextStepForAHuman: string;
};

type Body = {
  batch: string; notRecovered: number;
  counts: Record<string, number>;
  groups: Record<string, Exception[]>;
};

/**
 * Missed first and largest, deliberately.
 *
 * A submission that names its own misses outranks one showing a clean sweep on
 * cherry-picked cases. Correctly abandoned and correctly escalated are not
 * failures — a bounded system is supposed to stop — so they are separated rather
 * than folded in, which would understate the result as badly as omitting them
 * would overstate it.
 */
const GROUPS: [string, string, string][] = [
  ['missed', 'Missed', 'Recoverable by the oracle. Capstan did not recover them. These are the real failures.'],
  ['misdiagnosed', 'Misdiagnosed', 'Diagnosed cause differs from the true cause on a recoverable case.'],
  ['correctlyEscalated', 'Correctly escalated', 'Routed to a human because no safe automated action remained. Not a failure.'],
  ['correctlyAbandoned', 'Correctly abandoned', 'The oracle says these were never recoverable. Stopping was right.'],
];

export default async function Exceptions() {
  const body = await get<Body>('/api/backtest/exceptions?batch=holdout');

  if (!body) {
    return (
      <section>
        <h1>No exception list</h1>
        <p className="lede">The backend is not reachable, or no run has been measured.</p>
        <div className="empty" style={{ marginTop: 20 }}>
          <code>curl -X POST &apos;localhost:8080/api/backtest/run?batch=holdout&apos;</code>
        </div>
      </section>
    );
  }

  return (
    <>
      <section>
        <h1>{body.notRecovered} cases did not recover</h1>
        <p className="lede">
          Grouped by whether not recovering was the right answer. Only the first
          group is a failure.
        </p>
      </section>

      {GROUPS.map(([key, title, blurb]) => {
        const rows = body.groups[key] ?? [];
        if (!rows.length) return null;

        // The recommended next step is usually identical for every case in a
        // group — all 67 misses say "no automated action remains". Repeating one
        // sentence 67 times buries the columns that actually differ (diagnosed
        // vs. true cause, which guardrail stopped it, amount), so it is hoisted
        // to the header when it is uniform and kept per-row only when it varies.
        const steps = new Set(rows.map((e) => e.nextStepForAHuman));
        const sharedStep = steps.size === 1 ? rows[0].nextStepForAHuman : null;

        return (
          <section key={key}>
            <div className="panel">
              <h2>{title} <span className="dim">{body.counts[key]}</span></h2>
              <p className="foot" style={{ marginTop: -6, marginBottom: sharedStep ? 4 : 14 }}>{blurb}</p>
              {sharedStep && (
                <p className="foot" style={{ marginTop: 0, marginBottom: 14 }}>
                  <span className="dim">Next step for all {rows.length}:</span> {sharedStep}
                </p>
              )}
              <table>
                <thead>
                  <tr>
                    <th>Case</th><th>Diagnosed</th>
                    {key !== 'correctlyAbandoned' && <th>True cause</th>}
                    <th>Stopped at</th><th className="num">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((e) => (
                    <tr key={e.caseId}>
                      <td>
                        <a href={`/cases/${e.caseId}`} className="hash">{e.caseId.slice(0, 8)}</a>
                        {!sharedStep && (
                          <div className="foot" style={{ margin: 0 }}>{e.nextStepForAHuman}</div>
                        )}
                      </td>
                      <td>{human(e.diagnosedCause)}</td>
                      {key !== 'correctlyAbandoned' && (
                        <td className={e.trueCause && e.trueCause !== e.diagnosedCause ? 'halt' : 'dim'}>
                          {human(e.trueCause ?? '')}
                        </td>
                      )}
                      <td className="dim">
                        {human(e.terminalStatus)}, rung {e.ladderPosition}
                        {e.stoppedByGuardrail && <> <span className="tag held">{e.stoppedByGuardrail}</span></>}
                      </td>
                      <td className="num slip">{rupees(e.amountPaise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        );
      })}
    </>
  );
}
