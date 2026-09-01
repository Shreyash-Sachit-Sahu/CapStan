import RatchetScale from '@/components/RatchetScale';
import AblationWaterfall from '@/components/AblationWaterfall';
import { CostRow, EqualBudget, GuardrailTable, HeadlineMetrics, TerminalBar } from '@/components/BatchPanels';
import { BATCH, DEMO, get, ist, rupees } from '@/lib/api';

export const dynamic = 'force-dynamic';

type Report = {
  batch: string;
  sweep: { batches: string[]; median: Record<string, number>; iqr: Record<string, number> } | null;
  run: { batch: string; cases: number; atRiskPaise: number; inject: string; arms: Record<string, any> } | null;
  ablations: { attributionPp: Record<string, number>; liftPp: number } | null;
  equalBudget: Record<string, any>;
  generatedAt: string | null;
};

export default async function BatchView() {
  const report = await get<Report>(`/api/backtest/report?batch=${BATCH}`);

  if (!report || (!report.sweep && !report.run)) {
    return (
      <section>
        <h1>No measurement on file</h1>
        <p className="lede">
          The cockpit reads the last completed run rather than starting one. A run
          takes about eighty seconds and a judge looks for ninety.
        </p>
        <div className="empty" style={{ marginTop: 20 }}>
          Start the backend, then produce a measurement:
          <code>{`docker compose up -d --wait
cd backend && ./mvnw spring-boot:run

curl -X POST 'localhost:8080/api/admin/batch/load?batch=${BATCH}' \\
  -H 'Content-Type: application/json' --data-binary @fixtures/batch_${BATCH}.json
curl -X POST 'localhost:8080/api/backtest/prepare?batch=${BATCH}'
curl -X POST 'localhost:8080/api/backtest/run?batch=${BATCH}&inject=timeout_rate:0.05'`}</code>
        </div>
      </section>
    );
  }

  const sweep = report.sweep;
  const run = report.run;
  const arms = run?.arms;

  return (
    <>
      <section>
        <h1>Recovered under a ratchet, not a retry loop</h1>
        <p className="lede">
          Both arms are scored by the same oracle through the same simulated
          gateway. Absolute rupee figures are synthetic; the comparison and the
          ceiling beside it are the claim.
        </p>
        {arms?.baseline && arms?.capstan && (
          <div style={{ marginTop: 'var(--s-4)' }}>
            <HeadlineMetrics sweep={sweep} baseline={arms.baseline} capstan={arms.capstan} />
          </div>
        )}
      </section>

      {sweep && (
        <section>
          <RatchetScale
            label={`Share of value at risk recovered, median of ${sweep.batches.length} batches`}
            baseline={sweep.median.baseline}
            capstan={sweep.median.capstan}
            ceiling={sweep.median.upperBound}
          />
          {/* This table sits outside .panel, so it needs its own scroll container
              or it widens the document on a phone instead of scrolling itself. */}
          <div className="scrollx" style={{ marginTop: 16 }}>
          <table>
            <thead>
              <tr>
                <th>Arm</th><th className="num">Median</th><th className="num">IQR</th>
              </tr>
            </thead>
            <tbody>
              <tr><td className="dim">Fixed-ladder baseline</td>
                <td className="num dim">{(sweep.median.baseline * 100).toFixed(2)}%</td>
                <td className="num dim">{(sweep.iqr.baseline * 100).toFixed(2)}pp</td></tr>
              <tr><td>Capstan</td>
                <td className="num haul">{(sweep.median.capstan * 100).toFixed(2)}%</td>
                <td className="num haul">{(sweep.iqr.capstan * 100).toFixed(2)}pp</td></tr>
              <tr><td className="slip">Oracle ceiling</td>
                <td className="num slip">{(sweep.median.upperBound * 100).toFixed(2)}%</td>
                <td className="num slip">{(sweep.iqr.upperBound * 100).toFixed(2)}pp</td></tr>
            </tbody>
          </table>
          </div>
          <p className="foot">
            {sweep.batches.length} distinct fixtures, median. Every figure below
            this line is a single batch (<code className="hash">{run?.batch}</code>)
            and is labelled as such. The two are different measurements.
          </p>
        </section>
      )}

      {Object.keys(report.equalBudget).length > 0 && (
        <section><EqualBudget budgets={report.equalBudget} /></section>
      )}

      {report.ablations && (
        <section>
          <div className="panel">
            <h2>What each mechanism earned, single batch</h2>
            <AblationWaterfall data={report.ablations.attributionPp} />
          </div>
        </section>
      )}

      {arms?.baseline && arms?.capstan && (
        <section>
          <div className="grid2">
            <CostRow baseline={arms.baseline} capstan={arms.capstan} />
            <div>
              <TerminalBar capstan={arms.capstan} />
              <GuardrailTable blocks={arms.capstan.guardrailBlocks ?? {}} />
            </div>
          </div>
        </section>
      )}

      <section>
        <div className="panel">
          <h2>Open a case</h2>
          <table>
            <tbody>
              <tr>
                <td><a href={`/cases/${DEMO.timeout}`}>Lost response, reconciled, one debit</a>
                  <div className="foot">The gateway took the money and never said so.</div></td>
                <td className="num hash">{DEMO.timeout.slice(0, 8)}</td>
              </tr>
              <tr>
                <td><a href={`/cases/${DEMO.riskBlocked}`}>Model confidently wrong, debit refused anyway</a>
                  <div className="foot">The model was confidently wrong. A guardrail refused the debit anyway.</div></td>
                <td className="num hash">{DEMO.riskBlocked.slice(0, 8)}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="foot">
          {run && <>Batch <code className="hash">{run.batch}</code>, {run.cases} cases,{' '}
            {rupees(run.atRiskPaise)} at risk. Faults <code className="hash">{run.inject}</code>. </>}
          Measured {ist(report.generatedAt)}
        </p>
      </section>
    </>
  );
}
