# snetc

[![Test](https://github.com/pbsladek/snetc/actions/workflows/test.yml/badge.svg)](https://github.com/pbsladek/snetc/actions/workflows/test.yml)
[![Release](https://github.com/pbsladek/snetc/actions/workflows/release.yml/badge.svg)](https://github.com/pbsladek/snetc/actions/workflows/release.yml)
[![Codecov](https://codecov.io/gh/pbsladek/snetc/graph/badge.svg)](https://codecov.io/gh/pbsladek/snetc)

IPv4 and IPv6 subnet calculator for the command line.

## Install

### Pre-built binary

Download the binary for your platform from the [latest release](https://github.com/pbsladek/snetc/releases/latest):

| Platform | File |
|---|---|
| Linux x86\_64 | `snetc-linux-amd64` |
| Linux ARM64 | `snetc-linux-arm64` |
| macOS Apple Silicon | `snetc-macos-aarch64` |

Verify the checksum, then install:

```sh
sha256sum --check checksums.txt          # Linux
shasum -a 256 --check checksums.txt      # macOS

chmod +x snetc-linux-amd64
sudo mv snetc-linux-amd64 /usr/local/bin/snetc
```

### Build from source

**Requirements:** [Clojure CLI](https://clojure.org/guides/install_clojure) and [GraalVM JDK 21+](https://www.graalvm.org/) with `native-image`.

```sh
git clone https://github.com/pbsladek/snetc.git
cd snetc
make native          # compiles → dist/snetc
make native-smoke    # optional: smoke-tests the native binary
make bench           # optional: pure ops/TUI timing probes
make bench-native    # optional: native CLI timing probes
sudo cp dist/snetc /usr/local/bin/snetc
```

To run without compiling:

```sh
make run ARGS="192.168.0.0/22"
```

### Container image

The Docker image builds `snetc` as a GraalVM native executable and runs it from a Docker Hardened Images static runtime (`dhi.io/static:20250419-glibc-debian13`).

```sh
docker run --rm pwbsladek/snetc:latest 192.168.0.0/22
docker run --rm pwbsladek/snetc:latest classify 10.0.0.1 8.8.8.8
```

Build and push a tagged image locally:

```sh
make container-build TAG=tagname
make container-push TAG=tagname
```

The default image is intended for non-interactive commands. The interactive `snetc tree` TUI needs a shell and `stty`, so releases also publish a TUI-capable Docker Hardened Images Debian Base variant:

```sh
docker run --rm -it pwbsladek/snetc:latest-tui tree 10.0.0.0/24
make container-build-tui TAG=tagname
make container-run-tui TAG=tagname CIDR=10.0.0.0/24
```

The `-tui` image is still based on Docker Hardened Images, but it is not distroless because it includes the shell/coreutils needed by the terminal UI.

Tagged releases publish multi-architecture images to Docker Hub as `pwbsladek/snetc:<git-tag>`, `pwbsladek/snetc:<version-without-v>`, and `pwbsladek/snetc:latest`. TUI variants are published as `pwbsladek/snetc:<git-tag>-tui`, `pwbsladek/snetc:<version-without-v>-tui`, and `pwbsladek/snetc:latest-tui`. The workflow expects `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`; set `DHI_USERNAME` and `DHI_TOKEN` too if your Docker Hardened Images access uses separate credentials.

## Usage

```
snetc <cidr>                              Show info for an IPv4 or IPv6 subnet
snetc <cidr> --split <prefix>             List all /<prefix> subnets within <cidr>
snetc <cidr> --tree  <prefix>             Show split tree down to /<prefix>
snetc aggregate <cidr> [<cidr> ...]       Aggregate CIDRs to minimal covering set
snetc aggregate                           Read CIDRs from stdin (one per line)
snetc contains <cidr> <ip> [<ip> ...]     Check which IPs fall within a subnet
snetc free <parent> <alloc> [...]         Show unallocated space in a subnet
snetc allocate <parent> <hosts|/prefix> [...used]  Next IPv4 host block or IPv6 prefix
snetc plan <parent> <hosts|/prefix> [...] IPv4 VLSM by hosts; IPv6 by prefix
snetc overlaps <cidr> [<cidr> ...]        Detect overlapping/contained networks
snetc lpm <cidr|ip> ...                   Longest-prefix match (CIDRs=routes, IPs=lookups)
snetc diff <cidr> ... -- <cidr> ...       Diff two sets of CIDRs
snetc classify <ip-or-cidr> ...           RFC classification of IPs/CIDRs
snetc range <start-ip> <end-ip|+count>    Convert IP range to minimal CIDRs
snetc util <parent> <alloc> [...]         Visualise address space utilisation
snetc analyze [<file>]                    Analyse route table (or stdin)
snetc validate <ip-or-cidr> ...           Validate IPs/CIDRs
snetc tree <cidr>                         Interactive IPv4 split/join subnet planner
```

## Examples

### Subnet info

```
$ snetc 192.168.0.0/22
192.168.0.0/22
  Network:           192.168.0.0
  Broadcast:         192.168.3.255
  First Host:        192.168.0.1
  Last Host:         192.168.3.254
  Hosts:             1022
  Subnet Mask:       255.255.252.0
  Wildcard Mask:     0.0.3.255
```

### Split into subnets

Split and non-interactive tree rendering support IPv4 and IPv6. Non-interactive
set commands reject mixed IPv4/IPv6 inputs; the interactive `tree` TUI remains
IPv4-only.

```
$ snetc 192.168.0.0/22 --split 24
  Network/CIDR         Subnet Mask        First Host         Last Host          Broadcast          Hosts
--------------------------------------------------------------------------------------------------------------
  192.168.0.0/24       255.255.255.0      192.168.0.1        192.168.0.254      192.168.0.255      254
  192.168.1.0/24       255.255.255.0      192.168.1.1        192.168.1.254      192.168.1.255      254
  192.168.2.0/24       255.255.255.0      192.168.2.1        192.168.2.254      192.168.2.255      254
  192.168.3.0/24       255.255.255.0      192.168.3.1        192.168.3.254      192.168.3.255      254
```

### IPv6 subnet info

IPv6 output omits IPv4-only concepts such as broadcast, subnet mask, wildcard
mask, and usable host counts. Large JSON address counts are emitted as strings.

```
$ snetc 2001:db8::1/64
2001:db8::/64
  Network:           2001:db8::
  First Address:     2001:db8::
  Last Address:      2001:db8::ffff:ffff:ffff:ffff
  Addresses:         18446744073709551616
```

### Subnet tree

```
$ snetc 192.168.0.0/22 --tree 24
192.168.0.0/22  [1022 hosts]
├─ 192.168.0.0/23  [510 hosts]
│  ├─ 192.168.0.0/24  [254 hosts]
│  └─ 192.168.1.0/24  [254 hosts]
└─ 192.168.2.0/23  [510 hosts]
   ├─ 192.168.2.0/24  [254 hosts]
   └─ 192.168.3.0/24  [254 hosts]
```

### Aggregate

```
$ snetc aggregate 10.0.0.0/24 10.0.1.0/24
Aggregated 2 network(s) into 1:
  10.0.0.0/23
```

IPv6 aggregation uses the same command and requires one address family per
operation:

```
$ snetc aggregate 2001:db8::/64 2001:db8:0:1::/64
Aggregated 2 network(s) into 1:
  2001:db8::/63
```

### Check containment

```
$ snetc contains 192.168.0.0/22 192.168.1.1 10.0.0.1 192.168.3.255
Subnet: 192.168.0.0/22

  IP                   Match  Note
--------------------------------------------------
  192.168.1.1          yes
  10.0.0.1             no
  192.168.3.255        yes    (broadcast address)
```

### Free space

```
$ snetc free 10.0.0.0/24 10.0.0.0/26 10.0.0.128/26
Free space in 10.0.0.0/24 (excluding 2 allocated block(s)):

  CIDR                 Subnet Mask        Hosts
--------------------------------------------------
  10.0.0.64/26         255.255.255.192    62
  10.0.0.192/26        255.255.255.192    62
```

### VLSM planning

```
$ snetc plan 10.0.0.0/24 100 50 20
VLSM plan for 10.0.0.0/24

  #    Requested    Allocated            Subnet Mask        First Host       Last Host        Hosts
---------------------------------------------------------------------------------------------------------
  1    100          10.0.0.0/25          255.255.255.128    10.0.0.1         10.0.0.126       126
  2    50           10.0.0.128/26        255.255.255.192    10.0.0.129       10.0.0.190       62
  3    20           10.0.0.192/27        255.255.255.224    10.0.0.193       10.0.0.222       30
```

For IPv6, `plan` and `allocate` use prefix requests instead of host counts:

```
$ snetc plan 2001:db8::/60 /64 /64
IPv6 prefix plan for 2001:db8::/60

  #    Requested    Allocated                                   Addresses
--------------------------------------------------------------------------------
  1    /64          2001:db8::/64                               18446744073709551616
  2    /64          2001:db8:0:1::/64                           18446744073709551616
```

### Overlap detection

```
$ snetc overlaps 10.0.0.0/8 10.0.0.0/24 192.168.0.0/16 192.168.1.0/24
Checking 4 network(s) for overlaps...

  CIDR A                 CIDR B                 Relationship
----------------------------------------------------------------------
  10.0.0.0/8             10.0.0.0/24            A contains B
  192.168.0.0/16         192.168.1.0/24         A contains B

  2 overlap(s) found.
```

### Longest-prefix match

```
$ snetc lpm 10.0.0.1 10.0.0.0/8 10.0.0.0/16 10.0.0.0/24
Routing table: 3 route(s)

  IP                   Best Match             Prefix
-------------------------------------------------------
  10.0.0.1             10.0.0.0/24            /24
```

### Diff two sets of CIDRs

```
$ snetc diff 10.0.0.0/24 10.0.1.0/24 -- 10.0.0.0/24 10.0.2.0/24
Diff: 2 → 2 network(s)

  [=] 10.0.0.0/24
  [-] 10.0.1.0/24
  [+] 10.0.2.0/24

  Added: 1  Removed: 1  Unchanged: 1
```

### RFC classification

```
$ snetc classify 10.0.0.1 192.168.1.1 8.8.8.8 127.0.0.1
  Input                  Category                         RFC          Routable
--------------------------------------------------------------------------------
  10.0.0.1               Private                          RFC 1918     no
  192.168.1.1            Private                          RFC 1918     no
  8.8.8.8                Public                           -            yes
  127.0.0.1              Loopback                         RFC 1122     no
```

CIDRs that span multiple categories show the boundary:

```
$ snetc classify 10.0.0.0/7
  Input                  Category                         RFC          Routable
--------------------------------------------------------------------------------
  10.0.0.0/7             Private → Public                 RFC 1918     no
```

### IP range to CIDRs

```
$ snetc range 10.0.0.0 10.0.0.255
Range: 10.0.0.0 – 10.0.0.255  (256 addresses)

  10.0.0.0/24

  1 CIDR block(s)

$ snetc range 10.0.1.5 +50
Range: 10.0.1.5 – 10.0.1.54  (50 addresses)

  10.0.1.5/32
  10.0.1.6/31
  10.0.1.8/29
  10.0.1.16/28
  10.0.1.32/28
  10.0.1.48/30
  10.0.1.52/31
  10.0.1.54/32

  8 CIDR block(s)
```

### Interactive subnet planning

```
$ snetc tree 10.0.0.0/16
```

Opens a keyboard-driven terminal planner for manually carving a CIDR block into labelled subnets.

```
snetc tree: 10.0.0.0/16  (1 leaf subnet)

    #  Subnet              Mask               Range                             Hosts  Act  Lbl
----------------------------------------------------------------------------------------------
>     1  10.0.0.0/16         255.255.0.0        10.0.0.0..10.0.255.255            65534  s

up/down k/j  s split  J join  S split-to  H hosts  f filter  / search  : cmd  i import  e export  p print  q quit
Ready
```

Split the root once, then split the lower half, label the pieces, and the table updates in place:

```
snetc tree: 10.0.0.0/16  (3 leaf subnets)

    #  Subnet              Mask               Range                             Hosts  Act  Lbl
----------------------------------------------------------------------------------------------
      1  10.0.0.0/17         255.255.128.0      10.0.0.0..10.0.127.255            32766  s    Web tier
      2  10.0.128.0/18       255.255.192.0      10.0.128.0..10.0.191.255          16382  s/j  App tier
>     3  10.0.192.0/18       255.255.192.0      10.0.192.0..10.0.255.255          16382  s/j  DB tier

up/down k/j  s split  J join  S split-to  H hosts  f filter  / search  : cmd  i import  e export  p print  q quit
Labeled 10.0.192.0/18
```

The `Act` column shows what operations are available on the selected row: `s` (can split), `j` (can join with sibling), `s/j` (both), `-` (neither — /32 host route).

#### Key bindings

| Key | Action |
|---|---|
| `↑` / `k` / `←` | Move selection up |
| `↓` / `j` / `→` / `Tab` | Move selection down |
| `g` / `G` | Jump to first / last visible row |
| `Page Up` / `Page Down` | Move by one visible page |
| `s` / `Enter` / `Space` | Split selected subnet into two equal halves |
| `J` / `Backspace` / `Ctrl-H` | Join selected subnet with its sibling back into their parent |
| `S` | Split selected subnet down to a prompted prefix, for example `/26` |
| `H` | Split selected subnet to the smallest prefix that fits a prompted host count |
| `l` | Label selected subnet; blank input clears the label |
| `/` | Search and select by CIDR, contained IP, prefix, or label |
| `f` / `F` | Filter rows by CIDR, contained IP, prefix, or label |
| `Escape` | Clear the active filter |
| `u` / `U` | Undo |
| `r` / `R` | Redo |
| `:` | Open the command palette |
| `i` | Import a saved plan from EDN or JSON |
| `e` | Export plan; prompts for `edn`, `json`, or `yaml`, with an optional path |
| `p` / `P` | Write leaf CIDRs, one per line, to `snetc-leaves.txt` |
| `q` / `Q` / `Ctrl-C` | Quit |

Search and filter queries accept plain text, CIDRs, IP addresses, `/N` prefix shortcuts, and `@label` label shortcuts.

#### Command palette

Press `:` to run typed commands. Long forms and aliases are both supported:

| Command | Action |
|---|---|
| `split /N` / `s /N` | Split selected subnet down to prefix `/N` |
| `hosts N` / `h N` | Split selected subnet for `N` usable hosts |
| `join /N` / `j /N` | Join selected subnet upward toward prefix `/N` |
| `filter Q` / `f Q` | Filter rows by query `Q` |
| `clear` / `x` | Clear the active filter |
| `search Q` | Select the first row matching query `Q` |
| `label TEXT` | Set or clear the selected subnet label |
| `import PATH` | Import a saved EDN or JSON plan |
| `export [edn|json|yaml] [PATH]` | Export the current plan, optionally to `PATH` |
| `print` | Write all leaf CIDRs to `snetc-leaves.txt` |
| `print-selected` | Write the selected CIDR to `snetc-selected.txt` |
| `help` / `?` | Show command help |

#### Export formats

Pressing `e` prompts `Export [e]dn/[j]son/[y]aml (enter=edn):` and writes one of:

- **`snetc-plan.edn`** — full plan tree including labels; can be re-imported (round-trips via `import-plan`)
- **`snetc-plan.json`** — same structure as EDN, JSON-encoded
- **`snetc-plan.yaml`** — same structure, YAML-encoded

Example JSON output for the three-subnet plan above:

```json
{"version":1,"parent":"10.0.0.0/16","root":{"cidr":"10.0.0.0/16","label":null,"children":[{"cidr":"10.0.0.0/17","label":"Web tier","children":null},{"cidr":"10.0.128.0/17","label":null,"children":[{"cidr":"10.0.128.0/18","label":"App tier","children":null},{"cidr":"10.0.192.0/18","label":"DB tier","children":null}]}]}}
```

Pressing `p` instead writes a flat list of leaf CIDRs suitable for piping into other `snetc` commands:

```
10.0.0.0/17
10.0.128.0/18
10.0.192.0/18
```

#### Display modes

The table adapts to terminal width, dropping columns as space shrinks:

| Width | Columns shown |
|---|---|
| Wide | Subnet, Mask, Range, Usable IPs, Hosts, Act, Lbl |
| Standard | Subnet, Mask, Range, Hosts, Act, Lbl |
| Compact | Subnet, Range, Hosts, Act, Lbl |
| Narrow | Subnet, Hosts, Act, Lbl |

The existing non-interactive tree remains available as `snetc <cidr> --tree <prefix>`.

## Development

```sh
make test       # run tests
make spec       # run generative spec tests
make build      # build uberjar → target/snetc-0.1.0.jar
make native     # compile native binary → dist/snetc
make clean      # remove build artifacts
make changelog  # regenerate CHANGELOG.md (requires git-cliff)
make release    # bump patch version, tag, and push to trigger CI release
```

Release notes are generated automatically from commit messages when a tag is pushed. To preview them locally, run `git-cliff --latest` (install via `brew install git-cliff` or `cargo install git-cliff`). Commit message prefixes like `feat:`, `fix:`, `docs:`, `refactor:`, `test:` are used to group entries; unprefixed commits land in "Changes".

## License

MIT
