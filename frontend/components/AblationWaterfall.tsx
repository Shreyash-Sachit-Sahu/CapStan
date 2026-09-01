'use client';

import { Bar, BarChart, Cell, LabelList, ResponsiveContainer, XAxis, YAxis } from 'recharts';

/**
 * What each mechanism is worth, in points of rupee recovery.
 *
 * Anything under 1pp is one-to-two cases on a 300-case batch — a median ticket is
 * 0.34pp — so those bars are hatched and footnoted rather than drawn as though
 * they were measured to the same precision as the rest. A chart that renders a
 * one-case effect identically to a fifteen-point effect is claiming more than the
 * data supports.
 */
const RESOLUTION_FLOOR_PP = 1.0;

export default function AblationWaterfall({ data }: { data: Record<string, number> }) {
  const rows = Object.entries(data)
    .map(([name, pp]) => ({ name, pp: Number(pp.toFixed(2)) }))
    .sort((a, b) => b.pp - a.pp);

  const soft = rows.some((r) => Math.abs(r.pp) > 0 && Math.abs(r.pp) < RESOLUTION_FLOOR_PP);

  return (
    <>
      <svg width="0" height="0" aria-hidden>
        <defs>
          <pattern id="lowres" width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="5" height="5" style={{ fill: 'var(--slab)' }} />
            <line x1="0" y1="0" x2="0" y2="5" strokeWidth="1.6" opacity="0.55"
                  style={{ stroke: 'var(--haul)' }} />
          </pattern>
        </defs>
      </svg>
      {/* The 188px category gutter would eat a phone viewport, so the plot keeps a
          floor width and scrolls. The scroll container is here rather than on the
          enclosing .panel so containment does not depend on the parent's styling. */}
      <div style={{ overflowX: 'auto' }}>
      <div style={{ width: '100%', minWidth: 520, height: rows.length * 34 + 26 }}>
        <ResponsiveContainer>
          {/* The right margin has to clear the widest label — "+15.50pp" is ~52px at
              12px — and 46 clipped it mid-character. The negative end is padded to
              1.6x so a left-placed label has somewhere to sit that is not the axis. */}
          <BarChart data={rows} layout="vertical" margin={{ left: 0, right: 68, top: 4, bottom: 4 }}>
            <XAxis type="number" hide domain={[Math.min(0, ...rows.map((r) => r.pp)) * 1.6, 'dataMax']} />
            <YAxis type="category" dataKey="name" width={188} axisLine={false} tickLine={false}
                   tick={{ style: { fill: 'var(--muted)', fontSize: 12 } }} />
            <Bar dataKey="pp" barSize={15} isAnimationActive={false}>
              {rows.map((r) => (
                <Cell key={r.name}
                      fill={Math.abs(r.pp) < RESOLUTION_FLOOR_PP && r.pp !== 0
                        ? 'url(#lowres)' : undefined}
                      style={Math.abs(r.pp) < RESOLUTION_FLOOR_PP && r.pp !== 0
                        ? undefined
                        : { fill: r.pp < 0 ? 'var(--slip)' : 'var(--haul)' }} />
              ))}
              {/* Labels are placed by sign. A negative bar grows leftward, so
                  Recharts' "right" position puts its label back at the zero line —
                  directly on top of the category name. Two lists, each blanking the
                  other's sign, is the fix that keeps both ends outside the bar. */}
              <LabelList dataKey="pp" position="right"
                         formatter={(v: number) => (v >= 0 ? `+${v.toFixed(2)}pp` : '')}
                         style={{ fill: 'var(--text)', fontSize: 12, fontVariantNumeric: 'tabular-nums' }} />
              <LabelList dataKey="pp" position="left"
                         formatter={(v: number) => (v < 0 ? `${v.toFixed(2)}pp` : '')}
                         style={{ fill: 'var(--slip)', fontSize: 12, fontVariantNumeric: 'tabular-nums' }} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      </div>
      {soft && (
        <p className="foot">
          Hatched bars are below 1pp. One case at the median ticket is 0.34pp of a
          300-case batch, so those are one-to-two-case effects at the resolution
          limit. The ten-seed sweep is the better read for anything that small.
        </p>
      )}
    </>
  );
}
