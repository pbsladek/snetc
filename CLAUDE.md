# snetc — Claude Code context

## What this project is

`snetc` is a command-line IPv4 subnet calculator written in Clojure. It compiles to a GraalVM native binary via `make native` and also ships as a standard uberjar. All domain logic lives in pure namespaces; `core.clj` is a thin CLI dispatcher.

## Project layout

```
src/snetc/
  ip.clj        — pure bit-math: ip->long, long->ip, masks, network/broadcast/host math
  subnet.clj    — CIDR parsing (parse-cidr), subnet-info, range<->CIDR conversion, split/tree
  ops.clj       — set operations: aggregate, free-space, cidr-diff, find-overlaps, lpm, plan-vlsm
  classify.clj  — RFC classification of IPs and CIDRs against special-ranges table
  plan.clj      — pure split/join/label/export model for the interactive planner
  display.clj   — all terminal output; receives pre-computed data from handlers (no domain logic)
  tui_render.clj — pure row/table rendering helpers for the interactive planner
  tui.clj       — raw terminal event loop for snetc tree <cidr>
  spec.clj      — clojure.spec.alpha specs + generators for all public functions
  core.clj      — CLI entry point: parse-opts dispatch, handler functions, -main

test/snetc/
  ip_test.clj       — unit tests for ip.clj
  subnet_test.clj   — unit tests for subnet.clj
  ops_test.clj      — unit tests for ops.clj
  classify_test.clj — unit tests for classify.clj
  plan_test.clj     — unit tests for the interactive planner model
  tui_render_test.clj — unit tests for planner row rendering and viewport logic
  core_test.clj     — unit tests for CLI handler validation
  spec_test.clj     — data conformance + generative tests via stest/check (75 trials each)
```

## Commands

```sh
make test          # run all unit tests (kaocha)
make spec          # run generative spec tests only
make run ARGS="…"  # run via clojure -M -m snetc.core
make run ARGS="tree 10.0.0.0/16" # run interactive split/join planner
make build         # build uberjar → target/snetc-0.1.0.jar
make native        # compile GraalVM native binary → dist/snetc
make clean         # remove build artifacts
```

Tests run with: `bin/kaocha` (unit) or `bin/kaocha --focus snetc.spec-test` (spec/generative).

## Key design decisions

- **display.clj is a pure output layer.** Handlers in `core.clj` do all computation and validation before calling display functions. Display functions never call domain logic (no `subnet/subnet-info`, `classify/classify`, etc. inside display).
- **`/32` host routes omit `:broadcast`.** `subnet-info` uses `cond->` to only assoc `:broadcast` when `prefix < 32`. All display code handles nil `:broadcast` gracefully.
- **`special-ranges` is self-ordering.** The table in `classify.clj` is sorted at construction time by `:prefix` descending so manual ordering of the literal list is never required for correctness.
- **Lazy seqs are forced at boundaries.** `aggregate` and `find-overlaps` return vectors. The stdin path in `handle-aggregate` forces to `vec` before passing to both `count` and `aggregate`.
- **`--` separator for `diff`.** `-main` pre-splits `argv` on `"--"` before calling `parse-opts` so the separator reaches `handle-diff` as `diff-rhs`. `pre-args` uses `(not= sep-idx -1)` (not `pos?`) to handle `--` at index 0.
- **`new-prefix` is validated before bit-shift.** `split-subnets` calls `valid-prefix?` on `new-prefix` before any arithmetic to prevent a silent negative bit-shift for values > 32.
- **`ip->long` validates octet count and range.** Uses `str/split` with limit `-1` to catch trailing empty segments, checks exactly 4 parts, and verifies each octet is in `[0, 255]`.

## Spec coverage

`spec.clj` covers all public functions in `ip`, `subnet`, `ops`, and `classify` with `s/fdef`. Generators are defined for all primitive types (`::ip-long`, `::prefix`, `::ip-str`, `::cidr-str`, `::ip-range`, `::host-count`). The `::subnet-info-map` spec has `:broadcast` in `:opt-un` (absent for `/32`).

## Error handling convention

- All user-facing errors go through `die` in `core.clj` (prints to stderr, exits 1).
- Domain functions throw `ex-info` with a `:cidr`/`:ip`/`:mask` data key.
- `ex-message` is used to surface domain errors to the user via `die`.
- `die` must never be used as a return value inside a `let` binding or `mapv` callback — call it sequentially.

## CI / release

- `.github/workflows/test.yml` — runs `bin/kaocha` on every push/PR to `main`
- `.github/workflows/release.yml` — builds native binaries for linux-amd64, macos-amd64, macos-aarch64 on tag push; uses `graalvm/setup-graalvm@v1` and `DeLaGuardo/setup-clojure@13`
- Container: `Containerfile` is a two-stage build (builder: `clojure:temurin-21-tools-deps-alpine`, runtime: `eclipse-temurin:21-jre-alpine`); works with both Podman and Docker via `make container-build`/`make container-run`
