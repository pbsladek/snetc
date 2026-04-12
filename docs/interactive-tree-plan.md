# Interactive Subnet Tree Plan

## Goal

Add an interactive terminal subnet-planning mode inspired by David Croft's Visual Subnet Calculator:

https://www.davidc.net/networking/visual-subnet-calculator

The feature should let users start with a parent IPv4 CIDR, repeatedly split and join subnets, inspect subnet details live, and export the resulting plan. The important behavior is not rendering a fully expanded tree. It is maintaining a current partition of the parent network and letting the user refine that partition one operation at a time.

## Implementation Status

Implemented first cut:

- `snetc tree <cidr>` opens a keyboard-driven interactive planner.
- `snetc.plan` contains the pure split/join/label/export/import model.
- `snetc.tui_render` contains pure row rendering and viewport helpers.
- `snetc.tui` contains the dependency-free ANSI/raw terminal loop.
- Keyboard controls are implemented for select, split, join, label, undo, redo, jump, export, print leaf CIDRs, and quit.

Not implemented yet:

- mouse input
- `--load`, `--state`, `--export`, and `--no-mouse` CLI options
- JSON export
- compact bookmark-like state tokens
- direct split-to-prefix commands

## Proposed Command Shape

Prefer a new first-class subcommand:

```sh
snetc tree 10.0.0.0/16
```

This avoids overloading the existing static mode:

```sh
snetc 10.0.0.0/16 --tree 24
```

The existing `--tree <prefix>` should remain a static print command. Interactive mode should not require a target prefix because the user chooses splits manually.

Possible later options:

```sh
snetc tree 10.0.0.0/16 --load plan.edn
snetc tree 10.0.0.0/16 --state <token>
snetc tree 10.0.0.0/16 --no-mouse
snetc tree 10.0.0.0/16 --export plan.edn
```

## User Experience

Start state:

```text
snetc tree: 10.0.0.0/16

  #   Subnet              Mask              Range                         Usable IPs        Hosts    Action
--------------------------------------------------------------------------------------------------------------
> 1   10.0.0.0/16         255.255.0.0       10.0.0.0 - 10.0.255.255       10.0.0.1 - ...    65534    split

Commands: up/down select  s split  j join  l label  u undo  r redo  e export  q quit
```

After splitting `10.0.0.0/16`:

```text
  #   Subnet              Mask              Range                         Usable IPs        Hosts    Action
--------------------------------------------------------------------------------------------------------------
> 1   10.0.0.0/17         255.255.128.0     10.0.0.0 - 10.0.127.255       10.0.0.1 - ...    32766    split/join
  2   10.0.128.0/17       255.255.128.0     10.0.128.0 - 10.0.255.255     10.0.128.1 - ...  32766    split/join
```

The main view should show the current leaf subnets, optionally indented to make parent-child structure visible. It should not materialize the whole theoretical tree down to `/32`.

Keyboard controls should be complete even if mouse support is added:

| Key | Action |
|-----|--------|
| `up` / `down` or `k` / `j` | Move selection |
| `s` or `enter` | Split selected leaf into two children |
| `J` or `backspace` | Join selected leaf with sibling, if possible |
| `l` | Add or edit label |
| `u` | Undo |
| `r` | Redo |
| `/` | Search or jump to CIDR |
| `e` | Export plan |
| `p` | Print leaf CIDRs |
| `q` | Quit |

Mouse support can be added after keyboard support:

- click a row to select it
- click a `split` action to divide it
- click a `join` action to join it with its sibling

Terminal mouse support varies across terminal emulators, tmux, and SSH, so it should be optional.

## Architecture

Do not build this on the existing static `subnet-tree` expansion. That function is for printing a complete tree to a fixed prefix, and it now has a safety guard to prevent explosive expansion.

Add a pure planning namespace and keep terminal behavior separate:

```text
src/snetc/plan.clj          pure split/join/label/export model
src/snetc/tui.clj           terminal event loop
src/snetc/tui_render.clj    table/tree rendering helpers
```

`display.clj` should remain for current non-interactive terminal output. The interactive UI should have its own rendering namespace because it handles cursor position, screen refresh, scrolling, and event state.

## Data Model

Represent a plan as a persistent tree plus UI-independent metadata:

```clojure
{:parent "10.0.0.0/16"
 :root   {:cidr "10.0.0.0/16"
          :label nil
          :children nil}
 :cursor "10.0.0.0/16"
 :undo   []
 :redo   []}
```

Node shape:

```clojure
{:cidr "10.0.0.0/17"
 :label "web vlan"
 :children nil}
```

Internal nodes have two children:

```clojure
{:cidr "10.0.0.0/16"
 :label nil
 :children [{:cidr "10.0.0.0/17" :label nil :children nil}
            {:cidr "10.0.128.0/17" :label nil :children nil}]}
```

Core invariants:

- Leaves form a complete partition of the parent CIDR.
- Leaves never overlap.
- Leaves are sorted by network address.
- Only leaves can be split.
- A leaf can be joined only with its sibling.
- `/32` leaves cannot be split.
- Labels are user metadata and do not affect subnet math.

## Pure API Sketch

`snetc.plan` should be thoroughly tested before any TUI work:

```clojure
(new-plan parent-cidr)
(leaves plan)
(leaf? node)
(find-node plan cidr)
(can-split? plan cidr)
(split-leaf plan cidr)
(can-join? plan cidr)
(join-leaf plan cidr)
(label-leaf plan cidr label)
(export-plan plan)
(import-plan data)
(validate-plan plan)
```

Possible helpers:

```clojure
(sibling-cidr cidr)
(parent-cidr cidr)
(split-once cidr)
(plan-ranges plan)
```

`split-once` can reuse existing subnet logic:

```clojure
(subnet/split-subnets cidr (inc prefix))
```

but should return normalized child CIDR strings, not display maps.

## Rendering Model

The renderer should receive precomputed rows:

```clojure
{:cidr "10.0.0.0/17"
 :depth 1
 :selected? true
 :label "web vlan"
 :mask "255.255.128.0"
 :range "10.0.0.0 - 10.0.127.255"
 :usable "10.0.0.1 - 10.0.127.254"
 :hosts 32766
 :can-split? true
 :can-join? true}
```

The TUI layer should compute viewport rows from all leaves:

```clojure
(visible-rows rows cursor terminal-height scroll-offset)
```

This prevents large plans from forcing a full-screen redraw of every row.

## Terminal Library Options

Preferred spike: Lanterna.

Reasons:

- Java library, so it fits Clojure and GraalVM better than native ncurses bindings.
- Supports screen-style terminal UIs.
- Can support keyboard and mouse events.
- Avoids adding native dependencies.

Open questions before committing:

- Does Lanterna native-image cleanly with the existing GraalVM build?
- How much reflection config is needed?
- Does mouse input work acceptably in common terminals and tmux?
- Is the Clojure wrapper worth using, or should `snetc` call Lanterna directly?

Fallback option:

- Build a minimal ANSI raw-mode UI with keyboard only.
- Defer mouse support.
- This reduces dependency risk but increases terminal edge-case work.

## Save and Resume

First format: EDN.

```edn
{:version 1
 :parent "10.0.0.0/16"
 :root {:cidr "10.0.0.0/16"
        :children [{:cidr "10.0.0.0/17"
                    :label "web"
                    :children nil}
                   {:cidr "10.0.128.0/17"
                    :label "db"
                    :children nil}]}}
```

Later options:

- JSON export for interoperability
- compact base64-url token for bookmark-like sharing
- plain CIDR export for downstream tools

Possible commands:

```sh
snetc tree 10.0.0.0/16 --load plan.edn
snetc tree 10.0.0.0/16 --export plan.edn
snetc tree 10.0.0.0/16 --print-cidrs
```

## MVP Milestones

### 1. Pure Plan Model

Deliver:

- `src/snetc/plan.clj`
- unit tests for split, join, labels, export/import, and invariants
- no TUI yet

Acceptance:

- splitting a leaf replaces it with two child leaves
- joining siblings restores the parent leaf
- invalid split/join returns a clear error or no-op result
- all leaves remain sorted and non-overlapping
- `/32` split is rejected

### 2. Scriptable Plan Commands

Add a non-interactive way to exercise the model:

```sh
snetc plan-tree 10.0.0.0/16 --split 10.0.0.0/16 --split 10.0.0.0/17
```

This is optional but useful because it makes the model testable through CLI paths before terminal UI complexity.

Acceptance:

- command prints the current leaf table
- command can export EDN
- command validates invalid split/join operations with `die`

### 3. Keyboard TUI

Add:

```sh
snetc tree 10.0.0.0/16
```

Acceptance:

- opens full-screen terminal UI
- supports selection, split, join, undo, redo, quit
- handles terminal resize
- works with large plans by rendering only visible rows
- exits cleanly and restores terminal state

### 4. Labels and Export

Acceptance:

- edit labels in the TUI
- export EDN
- load EDN
- print current leaf CIDRs

### 5. Mouse Support

Acceptance:

- click row to select
- click split/join action to perform operation
- can disable mouse mode
- keyboard remains fully functional

## Test Plan

Pure tests:

- `new-plan` normalizes parent CIDR
- `leaves` returns the root initially
- `split-leaf` creates two adjacent children
- repeated splits preserve full parent coverage
- `join-leaf` only joins siblings
- joining after splitting restores the prior leaf
- labels survive unrelated split/join operations
- export/import round trips
- invalid operations return clear errors

CLI tests:

- missing parent fails
- invalid parent fails
- invalid split target fails
- invalid join target fails
- export writes valid EDN

TUI tests:

- keep most behavior in pure functions
- unit test key-event-to-action mapping
- unit test viewport calculations
- manually test terminal restore after errors

Native build checks:

```sh
make build
make native
dist/snetc tree 10.0.0.0/24
```

## Risks

- Mouse support can be inconsistent across terminal environments.
- Lanterna may need GraalVM reflection/resource configuration.
- Large plans can still be too big for humans even if memory-safe; the UI needs search/filter/jump.
- Labels and exported state introduce versioning concerns.
- A full-screen TUI is harder to test than the existing pure CLI flow.

## Open Questions

- Should the command be `snetc tree <cidr>` or `snetc interactive-tree <cidr>`?
- Should interactive mode be included in the native binary by default, or should it be behind a separate alias/dependency profile?
- Should split always divide into two equal children, or should users be able to split directly to a requested prefix?
- Should the join command join only exact siblings, or join all child leaves under a selected parent if the entire parent is currently subdivided?
- Should labels be attached only to leaves, or also to internal nodes?
- Should exported state include cursor/viewport/undo history, or only the subnet plan?
- Should a compact bookmark-like token be part of v1?

## Suggested First Implementation Cut

Start with `snetc.plan` only. Keep it pure and small.

Implement:

- `new-plan`
- `leaves`
- `split-leaf`
- `join-leaf`
- `label-leaf`
- `export-plan`
- `import-plan`
- invariant tests

Once this is solid, choose the terminal library and build the TUI around the pure model.
