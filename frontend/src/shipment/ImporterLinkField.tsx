import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { ApiError } from '../signup/api';
import { searchImporterOrganisations, type ImporterOrganisation } from './api';

export function ImporterLinkField({ token, query, onQuery, selected, onSelect, disabled, error, onSessionEnded }: {
  token: string;
  query: string;
  onQuery: (value: string) => void;
  selected: ImporterOrganisation | null;
  onSelect: (organisation: ImporterOrganisation) => void;
  disabled: boolean;
  error?: string;
  onSessionEnded: () => void;
}) {
  const listId = useId();
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<ImporterOrganisation[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [problem, setProblem] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [active, setActive] = useState(0);
  const requestSeq = useRef(0);
  const onSessionEndedRef = useRef(onSessionEnded);
  onSessionEndedRef.current = onSessionEnded;

  useEffect(() => {
    if (!open) return;
    const requestId = ++requestSeq.current;
    const handle = window.setTimeout(() => {
      setLoading(true);
      setItems([]);
      void searchImporterOrganisations(token, query.trim())
        .then(page => {
          if (requestId !== requestSeq.current) return;
          setItems(page.items);
          setTotal(page.total);
          setActive(0);
          setProblem('');
        })
        .catch(reason => {
          if (requestId !== requestSeq.current) return;
          setItems([]);
          setTotal(0);
          if (reason instanceof ApiError && reason.status === 401) {
            onSessionEndedRef.current();
            return;
          }
          setProblem(reason instanceof Error ? reason.message : 'Importer organisations could not be loaded.');
        })
        .finally(() => {
          if (requestId === requestSeq.current) setLoading(false);
        });
    }, query.trim() ? 250 : 0);
    return () => window.clearTimeout(handle);
  }, [open, query, token, attempt]);

  function choose(organisation: ImporterOrganisation) {
    onSelect(organisation);
    setOpen(false);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Escape') {
      setOpen(false);
      return;
    }
    if (!open || !items.length) return;
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive(index => (index + 1) % items.length);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive(index => (index - 1 + items.length) % items.length);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      choose(items[active] ?? items[0]);
    }
  }

  const describedBy = error ? 'link-importer-error' : problem ? 'link-importer-problem' : 'link-importer-hint';
  return (
    <div className="field importer-link">
      <label htmlFor="link-importer">Link importer</label>
      <input
        id="link-importer"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open && items[active] ? `${listId}-${items[active].id}` : undefined}
        value={query}
        placeholder="Search organisations"
        autoComplete="off"
        spellCheck={false}
        disabled={disabled}
        aria-invalid={Boolean(error)}
        aria-describedby={describedBy}
        onFocus={() => setOpen(true)}
        onChange={event => {
          onQuery(event.target.value);
          setOpen(true);
        }}
        onKeyDown={onKeyDown}
        onBlur={() => setOpen(false)}
      />
      {error
        ? <p className="field-error" id="link-importer-error">{error}</p>
        : <p className="field-hint" id="link-importer-hint">Search for the importer organisation that should see this shipment. Clear it and save to remove the link.</p>}
      {open && (
        <ul className="importer-results" id={listId} role="listbox" aria-label="Importer organisations">
          {loading ? <li className="importer-status" role="status">Searching organisations...</li> : null}
          {!loading && problem ? (
            <li className="importer-status" id="link-importer-problem" role="alert">
              {problem}<br />
              <button type="button" className="retry-button" onMouseDown={event => event.preventDefault()} onClick={() => setAttempt(value => value + 1)}>Try again</button>
            </li>
          ) : null}
          {!loading && !problem && items.length === 0 ? <li className="importer-status">No matching organisations.</li> : null}
          {!loading && !problem && items.map((item, index) => (
            <li key={item.id}>
              <button
                type="button"
                id={`${listId}-${item.id}`}
                role="option"
                aria-selected={selected?.id === item.id || index === active}
                onMouseDown={event => event.preventDefault()}
                onClick={() => choose(item)}
              >
                {item.name}
                <span>{item.code}</span>
              </button>
            </li>
          ))}
          {!loading && !problem && total > items.length ? <li className="importer-status">Showing the first {items.length} matches. Keep typing to narrow the list.</li> : null}
        </ul>
      )}
      {(selected || query) && (
        <div className="importer-link-actions">
          <button type="button" className="secondary-button" disabled={disabled} onClick={() => { onQuery(''); setOpen(false); }}>Remove link</button>
        </div>
      )}
    </div>
  );
}
