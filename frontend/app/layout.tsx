import type { Metadata } from 'next';
import { Space_Grotesk, IBM_Plex_Sans, IBM_Plex_Mono } from 'next/font/google';
import './globals.css';

// next/font downloads at build time and serves from /_next/static, so nothing
// fetches a font at page load. Phase 09 names wifi failure as a demo risk; a
// hero that goes blank when the venue drops is not acceptable.
const display = Space_Grotesk({ subsets: ['latin'], weight: ['500'], variable: '--font-display' });
const body = IBM_Plex_Sans({ subsets: ['latin'], weight: ['400', '500'], variable: '--font-body' });
const mono = IBM_Plex_Mono({ subsets: ['latin'], weight: ['400', '500'], variable: '--font-mono' });

export const metadata: Metadata = {
  title: 'Capstan · recovery cockpit',
  description: 'Bounded revenue recovery for failed recurring payments.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${display.variable} ${body.variable} ${mono.variable}`}>
      <body>
        <div className="shell">
          <header className="masthead">
            <a href="/" className="mark" style={{ textDecoration: 'none' }}>
              CAP<b>STAN</b>
            </a>
            <span style={{ color: 'var(--faint)', fontSize: 12 }}>recovery cockpit</span>
            <nav>
              <a href="/">Batch</a>
              <a href="/exceptions">Exceptions</a>
            </nav>
          </header>
          {children}
        </div>
      </body>
    </html>
  );
}
