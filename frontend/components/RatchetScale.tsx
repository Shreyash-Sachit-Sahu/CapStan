'use client';

import { useEffect, useState } from 'react';

/**
 * The signature object: one track from ₹0 to total-at-risk, with the two arms as
 * pawls and the oracle ceiling as a hard stop.
 *
 * A ratchet is the right metaphor because it is the honest one — it pulls value
 * back and holds it, and it cannot go past its stop. Putting the ceiling on the
 * same track as the result says "here is what we got and here is the most anyone
 * could have got" in a single object, which is a harder thing to fake than a
 * large number on a gradient.
 */
export default function RatchetScale({
  baseline, capstan, ceiling, label,
}: { baseline: number; capstan: number; ceiling: number; label: string }) {
  const [armed, setArmed] = useState(false);

  useEffect(() => {
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduce) { setArmed(true); return; }
    // Baseline first, then Capstan, so the eye reads the gap as it opens.
    const t = setTimeout(() => setArmed(true), 60);
    return () => clearTimeout(t);
  }, []);

  const w = (v: number) => `${(armed ? v * 100 : 0).toFixed(2)}%`;

  return (
    <div className="ratchet">
      <h2>{label}</h2>
      <div className="track" role="img"
           aria-label={`Baseline recovered ${(baseline * 100).toFixed(2)} percent, Capstan ${(capstan * 100).toFixed(2)} percent, oracle ceiling ${(ceiling * 100).toFixed(2)} percent of value at risk`}>
        <div className="pawl base" style={{ width: w(baseline), transitionDelay: '0ms' }} />
        <div className="pawl caps" style={{ width: w(capstan), transitionDelay: '260ms' }} />
        <div className="stop" style={{ left: `${ceiling * 100}%` }} />

        <span className="flag dim" style={{ left: `${baseline * 100}%` }}>
          baseline {(baseline * 100).toFixed(1)}%
        </span>
        <span className="flag haul" style={{ left: `${capstan * 100}%`, top: -52 }}>
          capstan {(capstan * 100).toFixed(1)}%
        </span>
        <span className="flag slip" style={{ left: `${ceiling * 100}%` }}>
          ceiling {(ceiling * 100).toFixed(1)}%
        </span>
      </div>
      <div className="scaleEnds">
        <span>₹0</span>
        <span>total at risk</span>
      </div>
    </div>
  );
}
