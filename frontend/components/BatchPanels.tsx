import { human, pct, rupees } from '@/lib/api';

type Arm = {
  recoveryRatePaise: number; recoveredPaise: number; debitAttempts: number;
  wastedAttempts: number; wastedAttemptRate: number; commsSent: number;
  commsPerRecovery: number | null; duplicateChargesCaused: number;
  safetyFailures: number; escalatedToHuman: number;
  guardrailBlocks: Record<string, number>; terminalBreakdown: Record<string, number>;
};

/**
 * The equal-budget table leads, ahead of the raw comparison.
 *
 * The headline claim is cost-per-recovery and safety, not recovery rate. Capped
 * at the same number of debits per case, Capstan wins at every budget; the
 * baseline only overtakes by spending attempts Capstan's policy declines, and the
 * scoring metric prices an attempt at zero.
 */
export function EqualBudget({ budgets }: { budgets: Record<string, { arms: Record<string, Arm> }> }) {
  const rows = Object.entries(budgets).sort(([a], [b]) => Number(a) - Number(b));
  if (!rows.length) return null;

  return (
    <div className="panel">
      <h2>Per attempt — both arms capped identically</h2>
      <table>
        <thead>
          <tr>
            <th>Budget</th>
            <th className="num">Baseline</th>
            <th className="num">Capstan</th>
            <th className="num">Delta</th>
            <th className="num">Attempts b / c</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(([n, r]) => {
            const b = r.arms.baseline, c = r.arms.capstan;
            const d = (c.recoveryRatePaise - b.recoveryRatePaise) * 100;
            return (
              <tr key={n}>
                <td>{n} debit{n === '1' ? '' : 's'} per case</td>
                <td className="num dim">{pct(b.recoveryRatePaise)}</td>
                <td className="num haul">{pct(c.recoveryRatePaise)}</td>
                <td className="num haul">{d >= 0 ? '+' : ''}{d.toFixed(2)}pp</td>
                <td className="num dim">{b.debitAttempts.toLocaleString()} / {c.debitAttempts.toLocaleString()}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="foot">
        At one attempt each, Capstan recovers more than twice what the baseline
        does from the identical single shot. Where the cap binds on both arms,
        Capstan is ahead at every budget.
      </p>
    </div>
  );
}

/** Costs sit next to the result, never below the fold. */
export function CostRow({ baseline, capstan }: { baseline: Arm; capstan: Arm }) {
  const cells: [string, string, string, boolean][] = [
    ['Debit attempts', baseline.debitAttempts.toLocaleString(), capstan.debitAttempts.toLocaleString(), true],
    ['Wasted attempt rate', pct(baseline.wastedAttemptRate), pct(capstan.wastedAttemptRate), true],
    ['Comms per recovery', baseline.commsPerRecovery?.toFixed(2) ?? '—', capstan.commsPerRecovery?.toFixed(2) ?? '—', true],
    ['Duplicate charges caused', String(baseline.duplicateChargesCaused), String(capstan.duplicateChargesCaused), false],
    ['Debits on fraud-blocked customers', String(baseline.safetyFailures), String(capstan.safetyFailures), false],
  ];
  return (
    <div className="panel">
      <h2>What it cost</h2>
      <table>
        <thead>
          <tr><th>Measure</th><th className="num">Baseline</th><th className="num">Capstan</th></tr>
        </thead>
        <tbody>
          {cells.map(([label, b, c, neutral]) => (
            <tr key={label}>
              <td>{label}</td>
              <td className={`num ${neutral ? 'dim' : 'slip'}`}>{b}</td>
              <td className={`num ${neutral ? '' : 'haul'}`}>{c}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="foot">
        This batch only. Both columns are measured and neither is a
        counterfactual — &quot;duplicate charges avoided&quot; would be one, so it is
        not shown. Across the ten-batch sweep the totals are 33 duplicates and
        270 fraud-blocked debits for the baseline, against 0 and 0.
      </p>
    </div>
  );
}

export function TerminalBar({ capstan }: { capstan: Arm }) {
  const order = ['RECOVERED', 'ESCALATED', 'ABANDONED', 'EXPIRED'];
  const colour: Record<string, string> = {
    RECOVERED: 'var(--haul)', ESCALATED: 'var(--slip)',
    ABANDONED: 'var(--faint)', EXPIRED: 'var(--halt)',
  };
  const entries = order.filter((k) => capstan.terminalBreakdown[k]);
  const total = entries.reduce((s, k) => s + capstan.terminalBreakdown[k], 0) || 1;

  return (
    <div className="panel">
      <h2>Where cases ended</h2>
      <div style={{ display: 'flex', height: 26, border: 'var(--rule)' }}>
        {entries.map((k) => (
          <div key={k} title={`${k} ${capstan.terminalBreakdown[k]}`}
               style={{ width: `${(capstan.terminalBreakdown[k] / total) * 100}%`, background: colour[k] }} />
        ))}
      </div>
      <table style={{ marginTop: 14 }}>
        <tbody>
          {entries.map((k) => (
            <tr key={k}>
              <td>
                <span style={{ display: 'inline-block', width: 8, height: 8, background: colour[k], marginRight: 8 }} />
                {human(k)}
                {k === 'ESCALATED' && <span className="dim"> — routed to a human, a correct outcome</span>}
              </td>
              <td className="num">{capstan.terminalBreakdown[k]}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const GUARDRAILS: Record<string, string> = {
  // Mirrors Guardrails.all() in the backend, verbatim.
  G1: "Opt-out", G2: "Cycle boundary", G3: "Mandate validity", G4: "Mandate cap",
  G5: "Attempt cap", G6: "Cooling-off", G7: "Quiet hours", G8: "Comms frequency",
  G9: "Rail availability", G10: "Risk hold", G11: "Issuer circuit breaker",
  G12: "Re-authorisation pending",
};

/** The bounded-and-gated evidence, made countable. */
export function GuardrailTable({ blocks }: { blocks: Record<string, number> }) {
  const ids = Object.keys(GUARDRAILS);
  const total = ids.reduce((s, id) => s + (blocks[id] ?? 0), 0);
  return (
    <div className="panel">
      <h2>Guardrails that stopped something</h2>
      <table>
        <thead>
          <tr><th style={{ width: 46 }}>ID</th><th>Guardrail</th><th className="num">Fired</th></tr>
        </thead>
        <tbody>
          {ids.map((id) => (
            <tr key={id}>
              <td className="hash">{id}</td>
              <td className={blocks[id] ? '' : 'dim'}>{GUARDRAILS[id]}</td>
              <td className={`num ${blocks[id] ? 'halt' : 'dim'}`}>{blocks[id] ?? 0}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="foot">
        {total.toLocaleString()} recorded refusals on this batch. A log of
        completed actions cannot tell a bounded system apart from an unbounded one
        that happened not to hit a limit.
      </p>
    </div>
  );
}
