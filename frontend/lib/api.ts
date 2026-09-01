// The one place UTC becomes IST. The backend speaks UTC everywhere (Phase 01
// pins the JVM to it); this boundary exists here and nowhere else.
const IST = 'Asia/Kolkata';
const BASE = process.env.CAPSTAN_API ?? 'http://localhost:8080';

// Which batch the cockpit reads. The name is configuration, not a fact about
// the data, so it lives in one place instead of being retyped into every fetch.
export const BATCH = process.env.CAPSTAN_BATCH ?? 'holdout';

// Two curated deep links, so the demo opens the illustrative cases in one click
// rather than hunting for them on stage. These are pointers, not measurements:
// the pages they open read every value from the API, and nothing here asserts
// what those cases contain. Override them when the fixtures are regenerated.
export const DEMO = {
  timeout: process.env.CAPSTAN_CASE_TIMEOUT ?? '8fa8b03a-c0d7-4f16-b6a4-72345f949974',
  riskBlocked: process.env.CAPSTAN_CASE_RISK ?? 'a5225e40-474e-4a54-af3e-d35da24dacfa',
};

export async function get<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${BASE}${path}`, { cache: 'no-store' });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    // The cockpit renders an instruction rather than a stack trace when the
    // backend is down. Empty states are instructions, not decoration.
    return null;
  }
}

const DASH = '—';

export const rupees = (paise: number | null | undefined) =>
  paise == null ? DASH : `₹${(paise / 100).toLocaleString('en-IN', { maximumFractionDigits: 0 })}`;

export const pct = (v: number | null | undefined, dp = 2) =>
  v == null ? DASH : `${(v * 100).toFixed(dp)}%`;

export const pp = (v: number | null | undefined, dp = 2) =>
  v == null ? DASH : `${v >= 0 ? '+' : ''}${v.toFixed(dp)}pp`;

/**
 * Renders an instant in IST, with the zone in the label.
 *
 * Tolerates the numeric form because `decision_json` rows written before the
 * serializer was corrected stored instants as epoch *seconds*. `new Date()`
 * reads a bare number as milliseconds, so those rendered as January 1970 --
 * silently wrong rather than visibly broken, which is the worse failure.
 */
export function ist(value: string | number | null | undefined) {
  if (value == null || value === '') return DASH;

  const epochSeconds =
    typeof value === 'number' ? value
      : /^\d+(\.\d+)?$/.test(value) ? parseFloat(value)
        : null;

  const d = epochSeconds !== null ? new Date(epochSeconds * 1000) : new Date(value);
  if (Number.isNaN(d.getTime())) return DASH;

  return `${new Intl.DateTimeFormat('en-GB', {
    timeZone: IST, day: '2-digit', month: 'short', hour: '2-digit',
    minute: '2-digit', hour12: false,
  }).format(d)} IST`;
}

export const human = (constant: string | null | undefined) =>
  (constant ?? '').toLowerCase().replace(/_/g, ' ');
