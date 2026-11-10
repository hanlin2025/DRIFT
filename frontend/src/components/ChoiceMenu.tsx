import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';

export type Choice = { value: string; label: string; disabled?: boolean };

export function ChoiceMenu({ id, value, options, describedBy, onChange }: {
  id: string;
  value: string;
  options: Choice[];
  describedBy?: string;
  onChange: (value: string) => void;
}) {
  const listId = useId();
  const root = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const current = options.find(option => option.value === value);
  const enabled = options.filter(option => !option.disabled);

  useEffect(() => {
    if (!open) return;
    function close(event: MouseEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  function choose(option: Choice) {
    if (option.disabled) return;
    onChange(option.value);
    setOpen(false);
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Escape') {
      setOpen(false);
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!open) {
        setActive(Math.max(0, enabled.findIndex(option => option.value === value)));
        setOpen(true);
        return;
      }
      setActive(index => {
        const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
        return (next + enabled.length) % enabled.length;
      });
      return;
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (!open) {
        setOpen(true);
        return;
      }
      const option = enabled[active];
      if (option) choose(option);
    }
  }

  return (
    <div className="choice-menu" ref={root}>
      <button
        id={id}
        type="button"
        className="choice-menu-button"
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listId}
        aria-describedby={describedBy}
        onClick={() => setOpen(currentOpen => !currentOpen)}
        onKeyDown={onKeyDown}
      >
        <span>{current?.label ?? value}</span>
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 6l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      {open ? (
        <ul className="choice-menu-list" id={listId} role="listbox">
          {options.map(option => {
            const enabledIndex = enabled.findIndex(item => item.value === option.value);
            return (
              <li key={`${option.value}:${option.label}`}>
                <button
                  type="button"
                  role="option"
                  aria-selected={option.value === value}
                  aria-disabled={option.disabled || undefined}
                  disabled={option.disabled}
                  className={enabledIndex === active ? 'is-active' : undefined}
                  onClick={() => choose(option)}
                >
                  {option.label}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
