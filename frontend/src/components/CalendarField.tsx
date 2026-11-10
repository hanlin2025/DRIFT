import { useEffect, useId, useRef, useState } from 'react';

const WEEKDAYS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'] as const;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;

type View = 'days' | 'months' | 'years';

function pad(part: number) {
  return String(part).padStart(2, '0');
}

function readParts(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(value.trim());
  if (!match) return null;
  return {
    year: Number(match[1]),
    month: Number(match[2]) - 1,
    day: Number(match[3]),
    hour: Number(match[4] ?? 0),
    minute: Number(match[5] ?? 0),
    second: Number(match[6] ?? 0),
  };
}

function clock(value: string) {
  return readParts(value) ?? { year: 0, month: 0, day: 1, hour: 0, minute: 0, second: 0 };
}

function formatValue(year: number, month: number, day: number, hour: number, minute: number, second: number, withTime: boolean) {
  const date = `${year}-${pad(month + 1)}-${pad(day)}`;
  return withTime ? `${date} ${pad(hour)}:${pad(minute)}:${pad(second)}` : date;
}

function TimePart({ label, value, max, onChange }: {
  label: string;
  value: number;
  max: number;
  onChange: (next: number) => void;
}) {
  const [draft, setDraft] = useState<string | null>(null);
  const name = label.toLowerCase();

  function commit(text: string) {
    if (text === '') return;
    const next = Number(text);
    if (!Number.isNaN(next)) onChange(Math.min(max, next));
  }

  return (
    <div className="calendar-time-part">
      <span>{label}</span>
      <div className="calendar-time-controls">
        <button type="button" aria-label={`Earlier ${name}`} onClick={() => onChange((value + max) % (max + 1))}>−</button>
        <input
          aria-label={label}
          inputMode="numeric"
          maxLength={2}
          value={draft ?? pad(value)}
        onFocus={event => {
          setDraft(String(value));
          event.currentTarget.select();
        }}
        onMouseUp={event => event.preventDefault()}
          onChange={event => {
            const digits = event.target.value.replace(/\D/g, '').slice(0, 2);
            setDraft(digits);
            commit(digits);
          }}
          onBlur={() => {
            commit(draft ?? '');
            setDraft(null);
          }}
        />
        <button type="button" aria-label={`Later ${name}`} onClick={() => onChange((value + 1) % (max + 1))}>+</button>
      </div>
    </div>
  );
}

function monthCells(year: number, month: number) {
  const cells: Array<number | null> = Array.from({ length: new Date(year, month, 1).getDay() }, () => null);
  const count = new Date(year, month + 1, 0).getDate();
  for (let day = 1; day <= count; day += 1) cells.push(day);
  return cells;
}

export function CalendarField({ id, value, pickerLabel, withTime = false, required = false, disabled = false, invalid = false, describedBy, onChange, onBlur }: {
  id: string;
  value: string;
  pickerLabel: string;
  withTime?: boolean;
  required?: boolean;
  disabled?: boolean;
  invalid?: boolean;
  describedBy?: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
}) {
  const panelId = useId();
  const root = useRef<HTMLDivElement>(null);
  const selected = readParts(value);
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<View>('days');
  const [visible, setVisible] = useState(() => selected ? new Date(selected.year, selected.month, 1) : new Date());

  useEffect(() => {
    if (!open) return;
    function close(event: MouseEvent) {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  function openPanel() {
    const current = readParts(value);
    setVisible(current ? new Date(current.year, current.month, 1) : new Date());
    setView('days');
    setOpen(true);
  }

  function chooseDay(day: number) {
    const time = clock(value);
    onChange(formatValue(visible.getFullYear(), visible.getMonth(), day, time.hour, time.minute, time.second, withTime));
    if (!withTime) setOpen(false);
  }

  function setTime(part: 'hour' | 'minute' | 'second', next: number) {
    const time = clock(value);
    const date = selected ?? { year: visible.getFullYear(), month: visible.getMonth(), day: visible.getDate() };
    onChange(formatValue(
      date.year,
      date.month,
      date.day,
      part === 'hour' ? next : time.hour,
      part === 'minute' ? next : time.minute,
      part === 'second' ? next : time.second,
      true,
    ));
  }

  const year = visible.getFullYear();
  const month = visible.getMonth();
  const yearPage = Math.floor(year / 12) * 12;
  const monthLabel = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric' }).format(new Date(year, month, 1));
  const time = clock(value);

  return (
    <div className="calendar-field" ref={root}>
      <input
        id={id}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder={withTime ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'}
        value={value}
        required={required}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        onChange={event => onChange(event.target.value)}
        onBlur={onBlur}
      />
      <button type="button" className="calendar-field-button" aria-label={pickerLabel} aria-expanded={open} aria-controls={panelId} disabled={disabled} onClick={() => open ? setOpen(false) : openPanel()}>
        <svg viewBox="0 0 16 16" aria-hidden="true"><rect x="2.5" y="3.5" width="11" height="10" rx="1.5" fill="none" stroke="currentColor" strokeWidth="1.4" /><path d="M2.5 6.5h11M5.5 2.5v2M10.5 2.5v2" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" /></svg>
      </button>
      {open ? (
        <div className="calendar-panel" id={panelId}>
          <div className="calendar-heading">
            {view === 'days' ? <button type="button" aria-label="Previous month" onClick={() => setVisible(current => new Date(current.getFullYear(), current.getMonth() - 1, 1))}>‹</button> : null}
            {view === 'years' ? <button type="button" aria-label="Previous years" onClick={() => setVisible(current => new Date(Math.floor(current.getFullYear() / 12) * 12 - 1, current.getMonth(), 1))}>‹</button> : null}
            {view === 'months' ? <span /> : null}
            <button type="button" className="calendar-title" onClick={() => setView(current => current === 'days' ? 'years' : current === 'years' ? 'months' : 'days')}>
              {view === 'days' ? monthLabel : view === 'months' ? String(year) : `${yearPage} – ${yearPage + 11}`}
            </button>
            {view === 'days' ? <button type="button" aria-label="Next month" onClick={() => setVisible(current => new Date(current.getFullYear(), current.getMonth() + 1, 1))}>›</button> : null}
            {view === 'years' ? <button type="button" aria-label="Next years" onClick={() => setVisible(current => new Date(Math.floor(current.getFullYear() / 12) * 12 + 12, current.getMonth(), 1))}>›</button> : null}
            {view === 'months' ? <span /> : null}
          </div>
          {view === 'years' ? (
            <div className="calendar-choice-grid" role="grid" aria-label={`${yearPage} to ${yearPage + 11}`}>
              {Array.from({ length: 12 }, (_, index) => yearPage + index).map(item => (
                <button type="button" key={item} aria-pressed={item === year} onClick={() => { setVisible(new Date(item, month, 1)); setView('months'); }}>{item}</button>
              ))}
            </div>
          ) : null}
          {view === 'months' ? (
            <div className="calendar-choice-grid" role="grid" aria-label={String(year)}>
              {MONTHS.map((name, index) => (
                <button type="button" key={name} aria-pressed={index === month} onClick={() => { setVisible(new Date(year, index, 1)); setView('days'); }}>{name}</button>
              ))}
            </div>
          ) : null}
          {view === 'days' ? (
            <>
              <div className="calendar-weekdays" aria-hidden="true">{WEEKDAYS.map(day => <span key={day}>{day}</span>)}</div>
              <div className="calendar-days" role="grid" aria-label={monthLabel}>
                {monthCells(year, month).map((day, index) => day == null
                  ? <span key={`empty-${index}`} />
                  : <button type="button" key={day} aria-pressed={selected?.year === year && selected.month === month && selected.day === day} onClick={() => chooseDay(day)}>{day}</button>)}
              </div>
            </>
          ) : null}
          {withTime ? (
            <div className="calendar-time">
              <div className="calendar-time-row">
                <TimePart label="Hour" value={time.hour} max={23} onChange={next => setTime('hour', next)} />
                <TimePart label="Minute" value={time.minute} max={59} onChange={next => setTime('minute', next)} />
                <TimePart label="Second" value={time.second} max={59} onChange={next => setTime('second', next)} />
              </div>
              <button type="button" className="secondary-button" onClick={() => setOpen(false)}>Done</button>
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
