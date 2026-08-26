# IPv6 implementation plan

This file tracks the phased work to add IPv6 support to non-interactive `snetc`
commands. The interactive `snetc tree <cidr>` TUI is intentionally out of scope
until the pure address model and CLI behavior are stable.

## Goals

- Support IPv4 and IPv6 CIDRs in the non-TUI CLI.
- Preserve all existing IPv4 behavior and output compatibility unless a command
  is explicitly extended.
- Keep domain logic pure. Handlers in `core.clj` should validate, compute, and
  pass precomputed data to `display.clj`.
- Reject mixed IPv4/IPv6 inputs for set operations unless a command has a clear
  family-specific behavior.
- Avoid Java signed integer edge cases by using one internal numeric model that
  works for both 32-bit and 128-bit address spaces.

## Non-goals for the first IPv6 pass

- No support for `snetc tree <cidr>` with IPv6.
- No IPv6 TUI rendering, filtering, split, join, import, or export behavior.
- No attempt to treat IPv6 host planning exactly like IPv4 VLSM.
- No dotted masks or wildcard masks for IPv6.
- No broadcast address for IPv6.

## Architecture direction

Introduce a family-aware address layer instead of stretching `snetc.ip` beyond
its current IPv4 contract.

Proposed namespaces:

- `snetc.addr`: family-neutral parsing, formatting, prefix math, range math.
- `snetc.ipv4`: optional home for current IPv4-specific helpers, either moved
  from `snetc.ip` or wrapped by `snetc.addr`.
- `snetc.ipv6`: IPv6 parsing, formatting, normalization, and special handling.

Initial parsed shapes should be explicit:

```clojure
{:family :ipv4
 :bits 32
 :addr 3232235520N
 :prefix 24
 :cidr "192.168.0.0/24"}

{:family :ipv6
 :bits 128
 :addr 42540766411282592856903984951653826560N
 :prefix 32
 :cidr "2001:db8::/32"}
```

Use `BigInteger`/Clojure bigint values internally for both families at the new
address layer. IPv4 can still expose existing `ip->long` and `long->ip` for
compatibility, but new family-neutral operations should not depend on Java
`long`.

## Command support matrix

| Command | IPv6 phase | Notes |
|---|---:|---|
| `validate` | 1 | Report `:type` as `ipv4`, `ipv4-cidr`, `ipv6`, or `ipv6-cidr`. |
| `info` / bare CIDR | 1 | IPv6 output should omit broadcast, mask, and wildcard fields. |
| `--split` | 2 | Family-aware subnet splitting; IPv6 reports address ranges/counts. |
| `--tree` | 2 | Non-interactive split tree; TUI remains IPv4-only. |
| `contains` | 1 | Require CIDR and IP to share the same family. |
| `range` | 2 | Accept IPv6 start/end and `+count`; reject mixed families. |
| `aggregate` | 2 | Group by family or reject mixed families. Prefer reject first. |
| `overlaps` | 2 | Same-family only. |
| `lpm` | 2 | Same-family route table per lookup. Reject mixed route/input sets first. |
| `diff` | 2 | Same-family only. |
| `supernet` | 2 | Same-family only. |
| `free` | 3 | Same-family only; useful for IPv6 allocation tracking. |
| `allocate` | 3 | IPv6 requests use prefix sizes such as `/64`; IPv4 stays host-count based. |
| `plan` | 3 | IPv6 requests use prefix sizes such as `/64`; IPv4 stays VLSM host-count based. |
| `util` | 3 | IPv6 reports address counts and stringifies large JSON totals. |
| `classify` | 4 | Uses IPv6 special-ranges table from IANA/RFC references. |
| `analyze` | 4 | Parses IPv6 CIDRs and rejects mixed-family route sets. |
| `mask` | never/IPv4-only | Keep as IPv4 mask conversion unless a new IPv6 prefix helper is needed. |
| `tree` | out of scope | TUI remains IPv4-only for now. |

## Phase 1: address model and read-only commands

Status: implemented

- Add `snetc.addr` with:
  - address parsing for IPv4 and IPv6
  - CIDR parsing for `/0` through `/32` and `/0` through `/128`
  - canonical CIDR normalization
  - `network-addr`, `last-addr`, `cidr->range`, `ip-in-cidr?`
  - `range->cidrs` using bigint math
- Keep `snetc.ip` public functions intact for current IPv4 tests and callers.
- Extend `subnet-info` or add a family-neutral replacement that returns:
  - shared keys: `:family`, `:network`, `:first-address`, `:last-address`,
    `:addresses`, `:prefix`, `:cidr`
  - IPv4 compatibility keys: `:first-host`, `:last-host`, `:hosts`, `:mask`,
    `:wildcard`, optional `:broadcast`
- Update non-TUI handlers for:
  - `validate`
  - `info`
  - `contains`
- Update display code so IPv6 info output has IPv6-specific labels and no
  IPv4-only fields.
- Add unit tests and specs for IPv6 parsing, normalization, containment, and
  `info` output.

Implementation notes:

- `src/snetc/addr.clj` is the family-aware address layer.
- `info`, bare CIDR dispatch, `contains`, and `validate` now use `snetc.addr`.
- IPv4 JSON for `info` remains compatible with the previous shape.
- IPv6 JSON uses address-range keys and stringifies `:addresses` to avoid
  losing precision in large address counts.
- The interactive `tree` subcommand remains IPv4-only and fails early with a
  clear message for IPv6 input.

Acceptance criteria:

- Existing IPv4 behavior remains covered and passes.
- `snetc 2001:db8::1/64` normalizes to `2001:db8::/64`.
- `snetc contains 2001:db8::/64 2001:db8::1` reports a match.
- `snetc contains 2001:db8::/64 192.0.2.1` fails with a clear mixed-family
  error.

## Phase 2: same-family set operations

Status: implemented

- Move range-based operations to bigint/family-neutral helpers:
  - split
  - adjacent next/prev
  - non-interactive tree
  - aggregate
  - overlaps
  - diff
  - lpm
  - range
  - supernet
- Add a shared family validation helper for command handlers.
- Decide whether mixed-family lists are rejected or grouped. Start with reject
  for clearer CLI behavior.
- Keep IPv4 JSON fields stable.
- Add IPv6 JSON fixtures for every supported command.

Acceptance criteria:

- `snetc aggregate 2001:db8::/64 2001:db8:0:1::/64` returns
  `2001:db8::/63`.
- `snetc lpm 2001:db8::1 2001:db8::/32 2001:db8::/64` returns the `/64`.
- Mixed-family set inputs fail before domain operations run.

Implementation notes:

- `aggregate`, `overlaps`, `diff`, `lpm`, `range`, and `supernet` now support
  IPv6 and reject mixed-family inputs.
- `--split`, `--tree`, `next`, and `prev` now support IPv6 using the same
  family-aware address layer.
- `range` supports IPv6 start/end and `+count`; IPv6 JSON includes
  `"family":"ipv6"` and stringifies large totals.
- `free`, `allocate`, `plan`, and `util` remain Phase 3 because their IPv6
  allocation semantics are intentionally unresolved.

## Phase 3: allocation-style commands

Status: implemented

IPv6 allocation semantics:

- IPv4 behavior remains host-count based and unchanged.
- IPv6 allocation is prefix-size based. `allocate` and `plan` accept requests
  like `/64`, `/56`, and `/48` when the parent is IPv6.
- IPv6 host-count allocation is intentionally rejected because "usable hosts"
  is not a useful IPv6 planning primitive in the same way as IPv4 VLSM.
- Prefix requests must be the same size as, or narrower than, the parent prefix.
- Requests are allocated largest-first. For prefixes, that means smaller prefix
  numbers are allocated first.
- `free` and `util` operate on same-family CIDR subtraction for IPv4 and IPv6.
- Mixed-family allocation inputs are rejected before domain operations run.

Acceptance criteria:

- IPv6 allocation commands have documented semantics and focused tests.
- Existing IPv4 VLSM behavior remains unchanged.

Implementation notes:

- `free` supports IPv6 and reports address counts instead of masks/hosts.
- `util` supports IPv6 and stringifies large JSON address counts.
- `allocate <ipv6-parent> /prefix [...used]` returns the first aligned free
  block of that prefix size.
- `plan <ipv6-parent> /prefix [...]` returns a prefix allocation plan.

## Phase 4: IPv6 classification and route analysis

Status: implemented

- Add an IPv6 special-ranges table with source references in comments.
- Include at least:
  - `::1/128` loopback
  - `::/128` unspecified
  - `::ffff:0:0/96` IPv4-mapped
  - `64:ff9b::/96` IPv4/IPv6 translation
  - `100::/64` discard-only
  - `2001:db8::/32` documentation
  - `2002::/16` 6to4
  - `fc00::/7` unique local
  - `fe80::/10` link-local
  - `ff00::/8` multicast
- Update `classify` to dispatch by family and preserve IPv4 output.
- Extend `analyze` route parsing to find IPv6 CIDRs.

Implementation notes:

- `classify` now uses `snetc.addr` and dispatches classification by parsed
  address family.
- The IPv6 special-ranges table is sourced from IANA/RFC references noted in
  `src/snetc/classify.clj`.
- IPv4 text and JSON classify output remain compatible; IPv6 JSON adds
  `"family":"ipv6"`.
- `analyze` can parse IPv6 CIDRs from route-table-like text and then reuses the
  same family-aware aggregation, containment, and summarization logic.
- Mixed-family route analysis is rejected by the existing same-family set
  operation validation.

Acceptance criteria:

- `snetc classify 2001:db8::1 fc00::1 fe80::1 ::1` produces expected
  categories.
- IPv4 classification output remains unchanged.

## Test plan

- Add unit tests beside existing IPv4 tests rather than replacing them.
- Add property/spec tests for IPv6 parse/format/network/range round trips.
- Add mixed-family validation tests for every extended command.
- Add CLI handler tests for text and JSON output.
- Keep `bin/kaocha` and `bin/kaocha --focus snetc.spec-test` as required gates.

## Compatibility notes

- Do not change current `snetc.ip/ip->long` or `long->ip` behavior in early
  phases.
- Do not add `:broadcast` to IPv6 result maps.
- Do not add `:broadcast` back to IPv4 `/32`.
- Avoid lazy sequences at command/display boundaries; force vectors before
  counting and printing.
- `display.clj` remains formatting-only. IPv6 classification, normalization,
  and range math must stay in handlers/domain namespaces.

## Resolved decisions

- Mixed-family set operations reject all mixed input instead of grouping by
  family.
- IPv6 `info` shows exact address counts. JSON stringifies large counts to
  avoid precision loss.
- IPv6 `allocate` and `plan` prefer prefix-size requests such as `/64` and
  reject host-count requests.
- Canonical IPv6 output is deterministic and implemented in `snetc.addr` rather
  than relying on Java address formatting.
