import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../signup/api';
import { searchImporterOrganisations } from '../shipment/api';
import { AuthorisationsPage } from './AuthorisationsPage';
import { endImporterAuthorisation, grantImporterAuthorisation, listImporterAuthorisations } from './api';
import type { Session } from '../session/session';

vi.mock('../shipment/api', () => ({ searchImporterOrganisations: vi.fn() }));
vi.mock('./api', () => ({
  listImporterAuthorisations: vi.fn(),
  grantImporterAuthorisation: vi.fn(),
  endImporterAuthorisation: vi.fn(),
}));

const admin: Session = {
  token: 'admin-token',
  expiresAt: '2099-01-01T00:00:00Z',
  id: 9,
  fullName: 'Amina Rahman',
  email: 'amina@example.com',
  role: 'ADMIN',
  company: { id: 1, code: 'HARBOURLINE_DEMO', name: 'Harbourline Logistics (Demo)' },
};
const straits = { id: 2, code: 'STRAITS_FRESH_DEMO', name: 'Straits Fresh Imports (Demo)' };

describe('importer authorisations', () => {
  beforeEach(() => {
    vi.mocked(listImporterAuthorisations).mockResolvedValue([{ id: 7, importer: straits, active: true }]);
    vi.mocked(searchImporterOrganisations).mockResolvedValue({ items: [straits], page: 0, size: 20, total: 1 });
  });

  it('lists active authorisations and ends one without offering the control to anyone else', async () => {
    vi.mocked(endImporterAuthorisation).mockResolvedValue({ id: 7, importer: straits, active: false });
    const user = userEvent.setup();
    render(<MemoryRouter><AuthorisationsPage account={admin} onSignOut={vi.fn()} onSessionEnded={vi.fn()} /></MemoryRouter>);
    expect(await screen.findByText('Straits Fresh Imports (Demo)')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'End authorisation' }));
    expect(endImporterAuthorisation).toHaveBeenCalledWith('admin-token', 7);
    expect(await screen.findByRole('status')).toHaveTextContent('can no longer receive new shipments');
    expect(screen.queryByText('Straits Fresh Imports (Demo)')).not.toBeInTheDocument();
  });

  it('keeps the list when ending an authorisation is refused', async () => {
    vi.mocked(endImporterAuthorisation).mockRejectedValue(new ApiError('Only an administrator of this company can change importer authorisations', 403));
    const user = userEvent.setup();
    render(<MemoryRouter><AuthorisationsPage account={admin} onSignOut={vi.fn()} onSessionEnded={vi.fn()} /></MemoryRouter>);
    await screen.findByText('Straits Fresh Imports (Demo)');
    await user.click(screen.getByRole('button', { name: 'End authorisation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Only an administrator of this company can change importer authorisations');
    expect(screen.getByText('Straits Fresh Imports (Demo)')).toBeVisible();
  });

  it('grants an importer from search and leaves the list unchanged when the grant is refused', async () => {
    vi.mocked(listImporterAuthorisations).mockResolvedValue([]);
    vi.mocked(grantImporterAuthorisation).mockRejectedValueOnce(new ApiError('Invalid or inactive importer organisation selected.', 400))
      .mockResolvedValueOnce({ id: 8, importer: straits, active: true });
    const user = userEvent.setup();
    render(<MemoryRouter><AuthorisationsPage account={admin} onSignOut={vi.fn()} onSessionEnded={vi.fn()} /></MemoryRouter>);
    await screen.findByText('No importer is authorised yet.');
    await user.type(screen.getByLabelText('Find an importer'), 'Straits');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await user.click(await screen.findByRole('button', { name: 'Grant authorisation' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid or inactive importer organisation selected.');
    expect(screen.getByText('No importer is authorised yet.')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Grant authorisation' }));
    expect(await screen.findByText(/can receive shipments/)).toBeVisible();
    expect(grantImporterAuthorisation).toHaveBeenLastCalledWith('admin-token', 2);
  });
});
