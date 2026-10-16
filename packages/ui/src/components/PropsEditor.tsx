import React, { useState } from 'react';
import { Calendar, CheckSquare, Hash as HashIcon, Plus, Type, X } from 'lucide-react';
import { PropKind, PropValue, propKind, todayKey } from '../model';

const KINDS: { kind: PropKind; label: string; icon: typeof Type; init: () => PropValue }[] = [
  { kind: 'text', label: 'Text', icon: Type, init: () => '' },
  { kind: 'number', label: 'Number', icon: HashIcon, init: () => 0 },
  { kind: 'checkbox', label: 'Checkbox', icon: CheckSquare, init: () => false },
  { kind: 'date', label: 'Date', icon: Calendar, init: () => todayKey() },
];
export const kindIcon = (v: PropValue | undefined) => KINDS.find((k) => k.kind === propKind(v))!.icon;

/** Editor for one property value; the value's type picks the control. */
export const PropInput: React.FC<{ value: PropValue; name: string; suggestions?: string[]; onChange: (v: PropValue) => void }> = ({
  value,
  name,
  suggestions,
  onChange,
}) => {
  const kind = propKind(value);
  if (kind === 'checkbox') return <input type="checkbox" checked={value as boolean} aria-label={name} onChange={(e) => onChange(e.target.checked)} />;
  if (kind === 'number')
    return (
      <input
        className="prop-input"
        type="number"
        value={value as number}
        aria-label={name}
        onChange={(e) => e.target.value !== '' && !Number.isNaN(e.target.valueAsNumber) && onChange(e.target.valueAsNumber)}
      />
    );
  if (kind === 'date') return <input className="prop-input" type="date" value={value as string} aria-label={name} onChange={(e) => e.target.value && onChange(e.target.value)} />;
  const list = suggestions?.length ? `prop-${name}` : undefined;
  return (
    <>
      <input className="prop-input" value={value as string} placeholder="Empty" aria-label={name} list={list} onChange={(e) => onChange(e.target.value)} />
      {list && (
        <datalist id={list}>
          {suggestions!.map((s) => (
            <option key={s} value={s} />
          ))}
        </datalist>
      )}
    </>
  );
};

/** Property rows in a page's Info section, plus "Add property". */
export const PropsEditor: React.FC<{
  props: Record<string, PropValue>;
  /** Text values other pages use per property name, offered while typing (select-like). */
  suggestions: Record<string, string[]>;
  onChange: (props: Record<string, PropValue> | undefined) => void;
}> = ({ props, suggestions, onChange }) => {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const set = (next: Record<string, PropValue>) => onChange(Object.keys(next).length ? next : undefined);
  const add = (k: (typeof KINDS)[number]) => {
    const n = name.trim();
    if (n && !(n in props) && !['title', 'icon', 'tags', 'aliases'].includes(n.toLowerCase())) set({ ...props, [n]: k.init() });
    setName('');
    setAdding(false);
  };
  return (
    <>
      {Object.entries(props).map(([k, v]) => {
        const Icon = kindIcon(v);
        return (
          <div key={k} className="property-row">
            <span className="property-label">
              <Icon size={14} aria-hidden="true" /> {k}
            </span>
            <span className="prop-value">
              <PropInput value={v} name={k} suggestions={suggestions[k]} onChange={(nv) => set({ ...props, [k]: nv })} />
              <button
                type="button"
                className="prop-remove"
                aria-label={`Remove property ${k}`}
                onClick={() => {
                  const { [k]: _, ...rest } = props;
                  set(rest);
                }}
              >
                <X size={12} aria-hidden="true" />
              </button>
            </span>
          </div>
        );
      })}
      <div className="property-row">
        {adding ? (
          <span className="prop-add-form">
            <input
              className="prop-input"
              autoFocus
              value={name}
              placeholder="Property name"
              aria-label="Property name"
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') add(KINDS[0]);
                if (e.key === 'Escape') setAdding(false);
              }}
            />
            {KINDS.map((k) => (
              <button key={k.kind} type="button" className="prop-kind" disabled={!name.trim()} onClick={() => add(k)} title={`Add as ${k.label}`}>
                <k.icon size={13} aria-hidden="true" /> {k.label}
              </button>
            ))}
          </span>
        ) : (
          <button type="button" className="tag-add" onClick={() => setAdding(true)}>
            <Plus size={12} aria-hidden="true" /> Add property
          </button>
        )}
      </div>
    </>
  );
};
