# snetc — Agent onboarding

## Project overview

`snetc` is a pure-Clojure IPv4/IPv6 subnet calculator CLI that compiles to a GraalVM native binary. Pure namespaces handle address math, set operations, and planning; `core.clj` validates CLI input and passes computed results to `display.clj`. The interactive `tree` planner remains IPv4-only.

## Read these files first

| File | Why |
|------|-----|
| `src/snetc/ip.clj` | Foundation — all bit-math primitives |
| `src/snetc/addr.clj` | Family-aware IPv4/IPv6 parsing, canonical text, and CIDR math |
| `src/snetc/subnet.clj` | IPv4 CIDR utilities retained for the interactive planner and compatibility |
| `src/snetc/ops.clj` | Set operations (aggregate, diff, VLSM, overlaps, LPM) |
| `src/snetc/classify.clj` | RFC classification, `special-ranges` table |
| `src/snetc/plan.clj` | Pure model for interactive split/join subnet planning |
| `src/snetc/core.clj` | CLI entry point, all handler functions |
| `src/snetc/display.clj` | Terminal output only — no domain logic |
| `src/snetc/tui_model.clj` | Display rows, search, filters, and summary data for the planner |
| `src/snetc/tui_actions.clj` | Command palette aliases and input parsing |
| `src/snetc/tui_render.clj` | Pure table rendering helpers for the interactive planner |
| `src/snetc/tui.clj` | Raw terminal event loop for `snetc tree <cidr>` |
| `src/snetc/spec.clj` | Domain specs and generators for both address families |

## How to verify your work

```sh
bin/kaocha                                  # full test suite
bin/kaocha --focus snetc.spec-test           # specs; 75 trials per stest/check
```

Both must pass with 0 failures before any change is complete.

For dependency or build changes, also run `clojure -T:build uber` and
`make native-smoke` when GraalVM is available. CI generates coverage with
`bin/kaocha --plugin kaocha.plugin/cloverage --codecov` and runs performance
probes with `make bench`.

## Architecture rules — do not break these

1. **display.clj contains no domain logic.** It only formats and prints. Computation happens in handlers in `core.clj`. If you find yourself calling `addr/...`, `subnet/...`, `classify/classify`, or `ops/...` inside a `print-*` function, move that call to the handler.

2. **Handlers validate before dispatching.** Every `handle-*` function in `core.clj` must guard against nil/empty inputs with `die` before reaching domain functions, which would otherwise throw NPEs with unhelpful messages.

3. **`die` is not a value.** Its default implementation prints to stderr and calls `System/exit 1`; batch mode rebinds it to throw so later items can run. Use it as a standalone statement, never as a value in `let` bindings, `mapv`, or `map` callbacks. Use the pattern: parse → record failure → check failure → die.

4. **Lazy seqs must be forced at API boundaries.** `aggregate` and `find-overlaps` return vectors. Any lazy seq that is counted and iterated must be forced with `vec` first (the stdin path in `handle-aggregate` does this explicitly).

5. **`/32` host routes have no `:broadcast` key.** `subnet-info` omits `:broadcast` for `/32`. All display and spec code handles this with `(or (:broadcast info) "-")` / `:opt-un`. Do not add `:broadcast` back for `/32`.

6. **Validate prefixes before arithmetic and bound tree expansion.** IPv4 prefixes must be integers in 0–32; IPv6 prefixes must be integers in 0–128. Passing `new-prefix > 32` to `(bit-shift-left 1 (- new-prefix prefix))` produces a silent garbage result via Java's shift-count masking. `plan/split-leaf-count` validates a requested bulk split and rejects more than 65536 leaves before allocation. The TUI uses this same check before asking for confirmation.

7. **`special-ranges` sorts itself.** The list in `classify.clj` is sorted by `:prefix desc` at construction time via `->>` + `sort-by`. Never rely on manual ordering; never remove the sort.

8. **`--` separator handling.** `-main` pre-splits `argv` on `"--"` before calling `parse-opts`. The check is `(not= sep-idx -1)`, not `(pos? sep-idx)` — the latter incorrectly rejects `--` at index 0.

9. **Validate batch items independently.** Each item must be an object with a non-blank string `cmd` and an optional array of string `args`. An invalid item returns exit code 1 without stopping later items. Invalid top-level input fails the batch itself.

10. **Imported plans must be valid binary trees.** Every node must use a canonical IPv4 CIDR, and each branch must contain its two immediate child subnets in address order. Leaf coverage alone does not establish tree validity.

## Common gotchas

- `ip->long` validates octet count (exactly 4, using `str/split` with limit `-1`) and range (`[0, 255]`). If you change parsing logic, re-check both constraints.
- `addr/parse-ip` rejects leading zeros in standalone and embedded IPv4 octets. IPv6 literals may use compressed, padded, uppercase, or embedded IPv4 notation; returned text is canonical. Zone identifiers are not supported.
- `mask->prefix` validates contiguous masks by reconstructing the expected mask and comparing. Don't simplify this to a bare `Long/bitCount` call.
- Both CIDR parsers reject leading zeros and whitespace in prefixes. `subnet/parse-cidr` accepts up to two digits for IPv4; `addr/parse-cidr` accepts up to three and checks the address family's limit.
- `plan-vlsm` loop guards `(> pos pend)` before alignment arithmetic. After allocating the last block ending at `255.255.255.255`, pos becomes `2^32`; the guard must come before any arithmetic on pos.
- `print-classify-result` uses a dynamically computed column width so spanning CIDRs (`"Documentation TEST-NET-3 → Private"`) don't overflow into adjacent columns. Don't replace with a fixed-width format string.

## Namespace dependencies

```
ip          → addr, subnet
addr        → ops, classify
subnet      → ops, plan, tui-actions
plan        → tui-model
tui-model   → tui-render
plan, subnet, tui-actions, tui-model, tui-render → tui
addr, ip, subnet, ops, classify, display, tui   → core
addr, ip, subnet, ops, classify                → spec (testing only)
```

Arrows point from a dependency toward its consumers; the diagram shows the main
layers. `display.clj` only requires `clojure.string` and `clojure.data.json`.
It receives computed results and has no dependency on a domain namespace.
The TUI receives terminal operations through an adapter so tests can drive it
without opening `/dev/tty`.

## Spec notes

- `::subnet-info-map` has `:broadcast` in `:opt-un` (absent for `/32`).
- The `:fn` invariant for `subnet/subnet-info` skips the broadcast check when `prefix = 32`: `(or (= prefix 32) (<= network broadcast))`.
- Generators for `::ip-str` and `::cidr-str` produce only valid inputs (pass through `valid-ip?` and `parse-cidr` respectively).
- IPv6 generators combine boundary examples with random hextets, compression positions, text formats, embedded IPv4 suffixes, and prefixes across 0–128. Keep generated splits and trees bounded.
- `check-sym` in `spec_test.clj` runs 75 trials: `{:clojure.spec.test.check/opts {:num-tests 75}}`.

## Deps

| Dep | Purpose |
|-----|---------|
| `org.clojure/clojure 1.12.6` | Language |
| `org.clojure/tools.cli 1.4.256` | CLI option parsing in `core.clj` |
| `org.clojure/data.json 2.5.2` | CLI JSON output, batch input, and plan import/export |
| `lambdaisland/kaocha 1.91.1392` | Test runner (`:test` alias) |
| `lambdaisland/kaocha-cloverage 1.1.89` | Coverage plugin (`:test` alias) |
| `cloverage/cloverage 1.2.4` | Coverage instrumentation and reports (`:test` alias) |
| `org.clojure/test.check 1.1.3` | Generative testing via spec (`:test` alias) |
| `io.github.clojure/tools.build 0.10.14` | Uberjar build (`:build` alias) |
