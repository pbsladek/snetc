# TUI phased roadmap

This file tracks the phased TUI work requested after the first integration pass.
Keep it current when behavior lands or when new TUI risks are discovered.

## Phase 1: Rendering and Performance Foundation

Status: implemented

- [x] Add a frame/diff renderer so the terminal loop can write only changed rows.
- [x] Keep the existing full-screen renderer for first paint, resize, and tests.
- [x] Add layout-mode visibility in the title so compact/tiny column drops are clear.
- [x] Add lightweight render benchmark helpers and tests with generous assertions.
- [x] Patch cheap row changes in place where practical; rebuild full rows for
  structural tree changes where row index and joinability can change globally.

## Phase 2: Navigation and Planning Productivity

Status: implemented

- [x] Add query-based search/filter by CIDR, IP containment, prefix, and label.
- [x] Add bulk planning commands:
  - split selected leaf to `/N`
  - split selected leaf to the smallest prefix that fits `N` hosts
  - join selected leaf upward to `/N`
- [x] Add a command palette for typed commands so new actions do not require one key
  per feature.

## Phase 3: Session Workflow and Workspace Context

Status: implemented

- [x] Add interactive import/resume from EDN or JSON exports.
- [x] Add a compact summary line for selected subnet and current plan distribution.
- [x] Preserve scroll/selection intent across renders and plan mutations.
- [x] Extend scenario tests through the injected terminal adapter.

## Later Candidates

- True incremental row-patch updates for split/join, beyond the cheap label path.
- Platform-specific terminal adapters if non-POSIX support becomes a target.

## Polishing Pass

Status: implemented

- [x] Show active filters in the title and summary.
- [x] Clear active filters with Escape.
- [x] Add `g` / `G` first/last row navigation.
- [x] Add Page Up / Page Down navigation.
- [x] Show hidden columns in compact/narrow layouts.
- [x] Add command aliases: `s`, `h`, `j`, `f`, `x`, and `?`.
- [x] Improve import errors for missing files, parse failures, and invalid plans.
- [x] Support command-palette export path overrides.
- [x] Show undo/redo availability in the summary line.
- [x] Add `print-selected` to write the selected CIDR.
- [x] Add command help through `: ?` / `: help`.
- [x] Confirm very large bulk splits before expansion.
- [x] Normalize query aliases: `/N` for prefix and `@label` for labels.
- [x] Add loop-level tests for diff rendering behavior.
- [x] Move command parsing and aliases into `snetc.tui-actions`.

## Phase 4: Performance and Measurement

Status: implemented

- [x] Cache display-row derived fields used by filtering, summaries, and IP
  containment queries.
- [x] Cache filtered rows and table layout between idle repaints.
- [x] Patch label changes by replacing only the selected row.
- [x] Build bulk split subtrees directly and record one command-level undo
  snapshot.
- [x] Replace all-pairs overlap detection with an active interval scan.
- [x] Replace longest-prefix-match sorting with a single best-match reduction.
- [x] Add `make bench` for pure ops/TUI probes and `make bench-native` for
  native CLI probes.
- [x] Upload benchmark artifacts from CI.
