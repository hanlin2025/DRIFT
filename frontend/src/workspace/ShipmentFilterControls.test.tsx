import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { ShipmentFilterControls } from './ShipmentFilterControls';

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function NavigateToReferenceA() {
  const navigate = useNavigate();
  return <button type="button" onClick={() => navigate('?reference=A')}>Back to A</button>;
}

function renderControls(entry = '/freight-forwarder') {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <ShipmentFilterControls />
      <NavigateToReferenceA />
      <Location />
    </MemoryRouter>,
  );
}

describe('ShipmentFilterControls', () => {
  it('renders the supported controls with an implicit Active status default', () => {
    renderControls();

    expect(screen.getByRole('textbox', { name: 'Filter by shipment reference' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Filter by vessel' })).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Filter by customer' })).toBeInTheDocument();
    expect(screen.getByLabelText('Filter by arrival date')).toHaveAttribute('type', 'date');
    expect(screen.getByRole('button', { name: 'Apply filters' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('ACTIVE');
  });

  it('initializes from the URL and keeps typing as draft state until apply', async () => {
    const user = userEvent.setup();
    renderControls('/freight-forwarder?reference=HBL-1&vessel=Pacific&customer=ACME&arrivalDate=2026-10-12&status=ARCHIVED');

    const reference = screen.getByRole('textbox', { name: 'Filter by shipment reference' });
    expect(reference).toHaveValue('HBL-1');
    expect(screen.getByRole('textbox', { name: 'Filter by vessel' })).toHaveValue('Pacific');
    expect(screen.getByRole('textbox', { name: 'Filter by customer' })).toHaveValue('ACME');
    expect(screen.getByLabelText('Filter by arrival date')).toHaveValue('2026-10-12');
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('ARCHIVED');

    await user.clear(reference);
    await user.type(reference, 'HBL-2');
    expect(screen.getByTestId('location')).toHaveTextContent('?reference=HBL-1&vessel=Pacific&customer=ACME&arrivalDate=2026-10-12');
  });

  it('applies normalized supported values together while preserving unrelated parameters', async () => {
    const user = userEvent.setup();
    renderControls('/freight-forwarder?view=table&reference=old');

    await user.clear(screen.getByRole('textbox', { name: 'Filter by shipment reference' }));
    await user.type(screen.getByRole('textbox', { name: 'Filter by shipment reference' }), ' HBL-2026 ');
    await user.type(screen.getByRole('textbox', { name: 'Filter by vessel' }), ' Pacific ');
    await user.type(screen.getByRole('textbox', { name: 'Filter by customer' }), ' ACME ');
    await user.type(screen.getByLabelText('Filter by arrival date'), '2026-10-12');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'ARCHIVED');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));

    expect(screen.getByTestId('location')).toHaveTextContent('?view=table&reference=HBL-2026&vessel=Pacific&customer=ACME&arrivalDate=2026-10-12&status=ARCHIVED');
    expect(screen.getByRole('textbox', { name: 'Filter by shipment reference' })).toHaveValue('HBL-2026');
  });

  it('omits blank values and clear removes only shipment filter parameters', async () => {
    const user = userEvent.setup();
    renderControls('/freight-forwarder?reference=HBL-1&vessel=Pacific&customer=ACME&arrivalDate=2026-10-12&status=ARCHIVED&view=table');

    await user.clear(screen.getByRole('textbox', { name: 'Filter by shipment reference' }));
    await user.type(screen.getByRole('textbox', { name: 'Filter by shipment reference' }), '   ');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(screen.getByTestId('location')).toHaveTextContent('?view=table&vessel=Pacific&customer=ACME&arrivalDate=2026-10-12');

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByTestId('location')).toHaveTextContent('?view=table');
    expect(screen.getByRole('textbox', { name: 'Filter by shipment reference' })).toHaveValue('');
    expect(screen.getByRole('textbox', { name: 'Filter by vessel' })).toHaveValue('');
    expect(screen.getByRole('textbox', { name: 'Filter by customer' })).toHaveValue('');
    expect(screen.getByLabelText('Filter by arrival date')).toHaveValue('');
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('ACTIVE');
  });

  it('resynchronizes draft controls when navigation changes applied URL filters', async () => {
    const user = userEvent.setup();
    renderControls('/freight-forwarder?reference=B');

    const reference = screen.getByRole('textbox', { name: 'Filter by shipment reference' });
    await user.clear(reference);
    await user.type(reference, 'Draft');
    await user.click(screen.getByRole('button', { name: 'Back to A' }));

    expect(screen.getByTestId('location')).toHaveTextContent('?reference=A');
    expect(reference).toHaveValue('A');
  });

  it('preserves an invalid raw URL status until the user deliberately changes or clears it', async () => {
    const user = userEvent.setup();
    renderControls('/freight-forwarder?status=banana');

    const status = screen.getByRole('combobox', { name: 'Filter by status' });
    expect(status).toHaveValue('banana');
    expect(screen.getByRole('option', { name: 'Invalid status' })).toBeInTheDocument();
    await user.selectOptions(status, 'ARCHIVED');
    await user.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(screen.getByTestId('location')).toHaveTextContent('?status=ARCHIVED');
  });
});
