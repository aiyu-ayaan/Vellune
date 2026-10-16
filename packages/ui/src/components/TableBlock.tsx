import React, { useLayoutEffect, useRef } from 'react';
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Plus, Trash2 } from 'lucide-react';
import { addCol, addRow, dropCol, dropRow, parseTable, serializeTable, setCell } from '../table';
import { openContextMenu } from './ContextMenu';
import { parseInline } from '../inline';

/** Spreadsheet-like editor for a table block; the first row is the header. */
export const TableBlock: React.FC<{ text: string; readOnly: boolean; onChange: (text: string) => void }> = ({ text, readOnly, onChange }) => {
  const grid = parseTable(text);
  const rows = grid.length;
  const cols = grid[0].length;
  const ref = useRef<HTMLTableElement>(null);
  const commit = (g: string[][]) => onChange(serializeTable(g));

  const cell = (r: number, c: number) => ref.current?.querySelector<HTMLInputElement>(`[data-cell="${r}:${c}"]`);
  // A cell that does not exist yet (row just added) is focused right after the next render.
  const pending = useRef<[number, number] | null>(null);
  useLayoutEffect(() => {
    if (!pending.current) return;
    const el = cell(...pending.current);
    if (el) {
      el.focus();
      pending.current = null;
    }
  });
  const focusCell = (r: number, c: number) => {
    const el = cell(r, c);
    if (el) el.focus();
    else pending.current = [r, c];
  };

  const onKey = (e: React.KeyboardEvent<HTMLInputElement>, r: number, c: number) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      e.stopPropagation();
      const i = r * cols + c + (e.shiftKey ? -1 : 1);
      if (i < 0) return;
      if (i >= rows * cols) {
        commit(addRow(grid));
        return focusCell(rows, 0);
      }
      focusCell(Math.floor(i / cols), i % cols);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (r === rows - 1) commit(addRow(grid));
      focusCell(r + 1, c);
    } else if (e.key === 'ArrowUp' && r > 0) {
      e.preventDefault();
      focusCell(r - 1, c);
    } else if (e.key === 'ArrowDown' && r < rows - 1) {
      e.preventDefault();
      focusCell(r + 1, c);
    }
  };

  const menu = (e: React.MouseEvent, r: number, c: number) => {
    if (readOnly) return;
    e.stopPropagation();
    openContextMenu(e, [
      { label: 'Insert row above', icon: ArrowUp, onSelect: () => commit(addRow(grid, r)), disabled: r === 0 },
      { label: 'Insert row below', icon: ArrowDown, onSelect: () => commit(addRow(grid, r + 1)) },
      { label: 'Insert column left', icon: ArrowLeft, onSelect: () => commit(addCol(grid, c)) },
      { label: 'Insert column right', icon: ArrowRight, onSelect: () => commit(addCol(grid, c + 1)) },
      'separator',
      { label: 'Delete row', icon: Trash2, danger: true, disabled: rows === 1 || r === 0, onSelect: () => commit(dropRow(grid, r)) },
      { label: 'Delete column', icon: Trash2, danger: true, disabled: cols === 1, onSelect: () => commit(dropCol(grid, c)) },
    ]);
  };

  return (
    <div className="table-block">
      <div className="table-scroll">
        <table ref={ref}>
          <tbody>
            {grid.map((row, r) => (
              <tr key={r} className={r === 0 ? 'table-head' : undefined}>
                {row.map((cell, c) =>
                  readOnly ? (
                    <td key={c}>{parseInline(cell).map((t) => t.text).join('')}</td>
                  ) : (
                    <td key={c} onContextMenu={(e) => menu(e, r, c)}>
                      <input
                        data-cell={`${r}:${c}`}
                        value={cell}
                        aria-label={`${r === 0 ? 'Header' : `Row ${r}`}, column ${c + 1}`}
                        onChange={(e) => commit(setCell(grid, r, c, e.target.value))}
                        onKeyDown={(e) => onKey(e, r, c)}
                      />
                    </td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
        {!readOnly && (
          <button type="button" className="table-add-col" title="Add column" aria-label="Add column" onClick={() => commit(addCol(grid))}>
            <Plus size={14} aria-hidden="true" />
          </button>
        )}
      </div>
      {!readOnly && (
        <button type="button" className="table-add-row" title="Add row" aria-label="Add row" onClick={() => commit(addRow(grid))}>
          <Plus size={14} aria-hidden="true" /> New row
        </button>
      )}
    </div>
  );
};
