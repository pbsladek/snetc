# TUI integration notes

This file tracks the working model for `snetc tree`, the renderer/TUI boundary,
and future integration improvements.

## Current architecture

- `snetc.plan` is the pure subnet-planning model. It owns the immutable tree,
  cursor, labels, undo/redo snapshots, import/export shape, and validation.
- `snetc.tui-model` builds display-ready rows from a plan. The interactive loop
  caches these rows in TUI state and refreshes them only when the plan changes.
  Rows include lower-cased labels/CIDRs, numeric address ranges, and prefixes so
  filtering and summaries do not reparse CIDRs during repaint.
- `snetc.tui-render` is pure screen rendering. It consumes `:rows` from state
  when present, falls back to computing rows for tests/backwards compatibility,
  accepts a precomputed layout when supplied, and emits one ANSI screen string
  with CRLF line endings for raw terminal mode.
- `snetc.tui` owns terminal integration: `/dev/tty`, raw mode, alternate screen,
  key reading, prompts, exports, and the event loop.

## Implemented integration improvements

- Row computation moved out of the renderer into `snetc.tui-model`.
- TUI state now caches row data so selection and repainting do not repeatedly
  parse CIDRs or search the plan tree.
- TUI state caches filtered rows and table layout per terminal width/row vector,
  so idle repaints avoid full-table filter/layout scans.
- Plan-changing commands refresh cached rows at the mutation boundary:
  split, join, label, undo, and redo.
- Label changes patch the cached row vector in place; structural tree changes
  still rebuild all rows because row index and joinability can change globally.
- Bulk split builds the descendant subtree directly and records a single undo
  snapshot.
- Terminal side effects are grouped behind an injectable adapter map. Tests can
  drive the event loop, prompts, and export behavior without using `/dev/tty`
  or writing files.
- Renderer layout now reserves a stable label column when space allows and marks
  clipped values with `>` instead of silently truncating.
- Rendering now produces explicit frames and a diff writer path, so the event
  loop can update only changed terminal rows after the first paint.
- The planner supports query search/filter, bulk split/join commands, command
  palette input, interactive import, summary display, and lightweight render,
  filter, layout, core-op, and native benchmark helpers.
- The command palette uses `snetc.tui-actions` for parsing and aliases. Current
  aliases are `s` split, `h` hosts, `j` join, `f` filter, `x` clear, and `?`
  help.
- Navigation supports first/last row (`g`/`G`), page movement, Escape filter
  clearing, selected-CIDR output, explicit export paths, and large-split
  confirmation.

## Current interaction flow

1. `core/handle-interactive-tree` validates CLI shape and calls `tui/run-tree!`.
2. `run-tree!` creates a `plan/new-plan`, captures terminal mode, enters the
   alternate screen, enables raw mode, and initializes cached rows.
3. Each loop builds a render frame, writes the full frame or a diff from the
   previous frame, reads one key, and passes it to `handle-key`.
4. Navigation updates selection and cursor against cached rows.
5. Plan operations update `:plan`, refresh `:rows`, then reselect by cursor.
6. Imports replace `:plan`, clear filters, refresh rows, and reselect by cursor.
7. Quit returns the final pure plan after restoring terminal mode and screen.

## Improvement backlog

- Add scenario-level test helpers that feed key streams through the injected
  terminal adapter and assert final state plus rendered screen fragments.
- Preserve scroll position more intentionally after split/join operations near
  the bottom of the viewport.
- Replace shell-based `stty` calls with a small platform abstraction if Windows
  or non-POSIX terminals become a target.
