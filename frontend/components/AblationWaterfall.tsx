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
            <rect width="5" height="5" fill="#1B222B" />
            <line x1="0" y1="0" x2="0" y2="5" stroke="#4FD1A5" strokeWidth="1.6" opacity="0.55" />
          </pattern>
        </defs>
      </svg>
      <div style={{ width: '100%', height: rows.length * 34 + 26 }}>
        <ResponsiveContainer>
          <BarChart data={rows} layout="vertical" margin={{ left: 0, right: 46, top: 4, bottom: 4 }}>
            <XAxis type="number" hide domain={[Math.min(0, ...rows.map((r) => r.pp)) * 1.15, 'dataMax']} />
            <YAxis type="category" dataKey="name" width={188} axisLine={false} tickLine={false}
                   tick={{ fill: '#8794A3', fontSize: 12 }} />
            <Bar dataKey="pp" barSize={15} isAnimationActive={false}>
              {rows.map((r) => (
                <Cell key={r.name}
                      fill={r.pp < 0 ? '#E8A33D'
                        : Math.abs(r.pp) < RESOLUTION_FLOOR_PP ? 'url(#lowres)' : '#4FD1A5'} />
              ))}
              <LabelList dataKey="pp" position="right"
                         formatter={(v: number) => `${v >= 0 ? '+' : ''}${v.toFixed(2)}pp`}
                         style={{ fill: '#DCE3EA', fontSize: 12, fontVariantNumeric: 'tabular-nums' }} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      {soft && (
        <p className="foot">
          Hatched bars are below 1pp. One case at the median ticket is 0.34pp of a
          300-case batch, so those are one-to-two-case effects at the resolution
          limit — the ten-seed sweep is the better read for anything that small.
        </p>
      )}
    </>
  );
}
