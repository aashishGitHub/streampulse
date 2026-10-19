import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { LiveChart } from './LiveChart';
import type { Series } from './types';

const series = (count: number, over: Partial<Series> = {}): Series => ({
  key: 'k',
  metric: 'CONCURRENT_VIEWERS',
  titleId: 'tt-9',
  region: 'eu-west-1',
  points: Array.from({ length: count }, (_, i) => ({
    time: Date.parse('2026-10-12T10:00:00Z') + i * 5_000,
    value: 100 + i,
    sampleCount: 10 + i,
  })),
  ...over,
});

describe('LiveChart', () => {
  it('names the chart by metric, title and region', () => {
    render(<LiveChart series={series(3)} />);
    const figure = screen.getByRole('figure', { name: /Concurrent viewers.*tt-9.*eu-west-1/ });
    expect(figure).toBeInTheDocument();
  });

  it('gives the graphic a text equivalent: latest value, range and point count', () => {
    render(<LiveChart series={series(3)} />);
    const image = screen.getByRole('img');
    expect(image).toHaveAccessibleName(/Concurrent viewers/);
    expect(image).toHaveAccessibleDescription(/latest 102 at 10:00:10 UTC.*lowest 100.*highest 102.*3 points/i);
  });

  it('shows the latest value as visible text too', () => {
    render(<LiveChart series={series(3)} />);
    expect(screen.getByText('102', { selector: '.chart-latest' })).toBeVisible();
  });

  it('offers a keyboard-operable table of recent values, newest first', async () => {
    const user = userEvent.setup();
    render(<LiveChart series={series(3)} />);
    const summary = screen.getByText(/data table/i);

    await user.tab();
    expect(summary).toHaveFocus();
    await user.keyboard('{Enter}');

    const rows = within(screen.getByRole('table')).getAllByRole('row');
    expect(rows).toHaveLength(4);
    expect(within(rows[1]!).getAllByRole('cell').map((c) => c.textContent)).toEqual(['10:00:10 UTC', '102', '12']);
  });

  it('lists at most the ten newest points in the table', async () => {
    render(<LiveChart series={series(40)} />);
    expect(within(screen.getByRole('table', { hidden: true })).getAllByRole('row', { hidden: true })).toHaveLength(11);
  });

  it('labels a series with no title as "all titles"', () => {
    render(<LiveChart series={series(1, { titleId: '' })} />);
    expect(screen.getByRole('figure', { name: /all titles/ })).toBeInTheDocument();
  });

  it('falls back to the raw metric name for a metric it has no label for', () => {
    render(<LiveChart series={series(1, { metric: 'SOMETHING_NEW' })} />);
    expect(screen.getByRole('figure', { name: /SOMETHING_NEW/ })).toBeInTheDocument();
  });
});
