# snetc — Agent onboarding

## Project in one sentence

`snetc` is a pure-Clojure IPv4 subnet calculator CLI that compiles to a GraalVM native binary. All logic is in pure namespaces; `core.clj` dispatches CLI args to handlers which pass pre-computed data to `display.clj`.

## Read these files first

| File | Why |
|------|-----|
| `src/snetc/ip.clj` | Foundation — all bit-math primitives |
| `src/snetc/subnet.clj` | CIDR parsing, `subnet-info`, range conversion |
| `src/snetc/ops.clj` | Set operations (aggregate, diff, VLSM, overlaps, LPM) |
| `src/snetc/classify.clj` | RFC classification, `special-ranges` table |
| `src/snetc/plan.clj` | Pure model for interactive split/join subnet planning |
| `src/snetc/core.clj` | CLI entry point, all handler functions |
| `src/snetc/display.clj` | Terminal output only — no domain logic |
| `src/snetc/tui_render.clj` | Pure table rendering helpers for the interactive planner |
| `src/snetc/tui.clj` | Raw terminal event loop for `snetc tree <cidr>` |
| `src/snetc/spec.clj` | Full spec coverage with generators |

## How to verify your work

```sh
bin/kaocha                                  # all unit tests (52 tests, ~439 assertions)
bin/kaocha --focus snetc.spec-test          # generative spec tests (6 tests, 75 trials each)
```

Both must pass with 0 failures before any change is complete.

## Architecture rules — do not break these

1. **display.clj contains no domain logic.** It only formats and prints. Computation happens in handlers in `core.clj`. If you find yourself calling `subnet/subnet-info`, `classify/classify`, `ops/...`, or `subnet/parse-cidr` inside a `print-*` function, move that call to the handler.

2. **Handlers validate before dispatching.** Every `handle-*` function in `core.clj` must guard against nil/empty inputs with `die` before reaching domain functions, which would otherwise throw NPEs with unhelpful messages.

3. **`die` is not a value.** `die` calls `System/exit 1` and must only appear as a standalone statement, never inside `let` bindings, `mapv`, or `map` callbacks. Use the pattern: parse → assign nil on failure → check nil → die.

4. **Lazy seqs must be forced at API boundaries.** `aggregate` and `find-overlaps` return vectors. Any lazy seq that is counted and iterated must be forced with `vec` first (the stdin path in `handle-aggregate` does this explicitly).

5. **`/32` host routes have no `:broadcast` key.** `subnet-info` omits `:broadcast` for `/32`. All display and spec code handles this with `(or (:broadcast info) "-")` / `:opt-un`. Do not add `:broadcast` back for `/32`.

6. **`split-subnets` must validate `new-prefix` before any bit arithmetic.** Call `valid-prefix?` first. Passing `new-prefix > 32` to `(bit-shift-left 1 (- new-prefix prefix))` produces a silent garbage result via Java's shift-count masking.

7. **`special-ranges` sorts itself.** The list in `classify.clj` is sorted by `:prefix desc` at construction time via `->>` + `sort-by`. Never rely on manual ordering; never remove the sort.

8. **`--` separator handling.** `-main` pre-splits `argv` on `"--"` before calling `parse-opts`. The check is `(not= sep-idx -1)`, not `(pos? sep-idx)` — the latter incorrectly rejects `--` at index 0.

## Common gotchas

- `ip->long` validates octet count (exactly 4, using `str/split` with limit `-1`) and range (`[0, 255]`). If you change parsing logic, re-check both constraints.
- `mask->prefix` validates contiguous masks by reconstructing the expected mask and comparing. Don't simplify this to a bare `Long/bitCount` call.
- `parse-cidr` rejects leading zeros and whitespace in the prefix via `#"0|[1-9]\d?"`. This regex also rejects 3-digit strings (correctly, since valid prefixes are 0–32).
- `plan-vlsm` loop guards `(> pos pend)` before alignment arithmetic. After allocating the last block ending at `255.255.255.255`, pos becomes `2^32`; the guard must come before any arithmetic on pos.
- `print-classify-result` uses a dynamically computed column width so spanning CIDRs (`"Documentation TEST-NET-3 → Private"`) don't overflow into adjacent columns. Don't replace with a fixed-width format string.

## Namespace dependency order

```
ip  ←  subnet  ←  ops
                ←  classify
                ←  spec (testing only)
                ←  display  ←  core
```

`display.clj` only requires `snetc.ip` and `snetc.subnet` (for `ip-in-cidr?` in the contains table). It does not require `ops` or `classify`.

## Spec notes

- `::subnet-info-map` has `:broadcast` in `:opt-un` (absent for `/32`).
- The `:fn` invariant for `subnet/subnet-info` skips the broadcast check when `prefix = 32`: `(or (= prefix 32) (<= network broadcast))`.
- Generators for `::ip-str` and `::cidr-str` produce only valid inputs (pass through `valid-ip?` and `parse-cidr` respectively).
- `check-sym` in `spec_test.clj` runs 75 trials: `{:clojure.spec.test.check/opts {:num-tests 75}}`.

## Deps

| Dep | Purpose |
|-----|---------|
| `org.clojure/clojure 1.12.0` | Language |
| `org.clojure/tools.cli 1.1.230` | CLI option parsing in `core.clj` |
| `lambdaisland/kaocha 1.91.1392` | Test runner (`:test` alias) |
| `org.clojure/test.check 1.1.1` | Generative testing via spec (`:test` alias) |
| `io.github.clojure/tools.build 0.10.6` | Uberjar build (`:build` alias) |
