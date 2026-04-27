---
layout: default
title: Home
nav_order: 1
---

# snetc

IPv4 subnet calculator for the command line. Computes subnet info, splits, trees, aggregation, VLSM, overlaps, diffs, LPM routing, RFC classification, and more — as a fast native binary with no runtime required.

**[View on GitHub](https://github.com/pbsladek/snetc)** · **[Latest release](https://github.com/pbsladek/snetc/releases/latest)**

---

## Install

### Pre-built binary

Download from the [latest release](https://github.com/pbsladek/snetc/releases/latest):

| Platform | File |
|---|---|
| Linux x86_64 | `snetc-linux-amd64` |
| Linux ARM64 | `snetc-linux-arm64` |
| macOS Apple Silicon | `snetc-macos-aarch64` |

Verify and install:

```sh
sha256sum --check checksums.txt          # Linux
shasum -a 256 --check checksums.txt      # macOS

chmod +x snetc-linux-amd64
sudo mv snetc-linux-amd64 /usr/local/bin/snetc
```

### Build from source

Requires [Clojure CLI](https://clojure.org/guides/install_clojure) and [GraalVM JDK 21+](https://www.graalvm.org/).

```sh
git clone https://github.com/pbsladek/snetc.git
cd snetc
make native
sudo cp dist/snetc /usr/local/bin/snetc
```

---

## Commands

```
snetc <cidr>                              Subnet info
snetc <cidr> --split <prefix>            List all /<prefix> subnets
snetc <cidr> --tree  <prefix>            Show subnet split tree
snetc aggregate <cidr> [...]             Aggregate to minimal covering set
snetc aggregate                          Read CIDRs from stdin
snetc contains <cidr> <ip> [...]         Check IP containment
snetc free <parent> <alloc> [...]        Show unallocated space
snetc plan <parent> <hosts> [...]        VLSM: allocate by host count
snetc overlaps <cidr> [...]              Detect overlapping networks
snetc lpm <cidr|ip> ...                  Longest-prefix match
snetc diff <cidr> ... -- <cidr> ...      Diff two CIDR sets
snetc classify <ip-or-cidr> ...          RFC classification
snetc range <start> <end|+count>         IP range to minimal CIDRs
snetc util <parent> <alloc> [...]        Address space utilisation map
snetc analyze [<file>]                   Route table analysis
snetc tree <cidr>                        Interactive subnet planner
```

---

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

### Utilisation map

```
$ snetc util 10.0.0.0/16 10.0.0.0/18 10.0.128.0/19 10.0.160.0/20

10.0.0.0/16  [65536 addresses]

  [################......................##########..............................]

  Allocated   10.0.0.0/18          255.255.192.0      16382 hosts
  Allocated   10.0.128.0/19        255.255.224.0      8190 hosts
  Allocated   10.0.160.0/20        255.255.240.0      4094 hosts
  Free        10.0.64.0/18         255.255.192.0      16382 hosts
  Free        10.0.176.0/20        255.255.240.0      4094 hosts
  Free        10.0.192.0/18        255.255.192.0      16382 hosts  <- largest

  Used: 28672 / 65536  (44%)  |  Free: 36864 in 3 blocks  |  Fragmentation: low
```

### Route table analysis

```
$ ip route show | snetc analyze

10 routes parsed  ->  2 after aggregation  (saves 8)

  Containment (6):
    10.0.1.0/24          c  10.0.0.0/8
    10.0.2.0/24          c  10.0.0.0/8
    ...

  Summarization opportunities (1):

    10.1.0.0/24          +--
    10.1.1.0/24          +->  10.1.0.0/23  (2 -> 1)
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

### Aggregate

```
$ snetc aggregate 10.0.0.0/24 10.0.1.0/24

Aggregated 2 network(s) into 1:
  10.0.0.0/23
```

### Diff two CIDR sets

```
$ snetc diff 10.0.0.0/24 10.0.1.0/24 -- 10.0.0.0/24 10.0.2.0/24

Diff: 2 -> 2 network(s)

  [=] 10.0.0.0/24
  [-] 10.0.1.0/24
  [+] 10.0.2.0/24

  Added: 1  Removed: 1  Unchanged: 1
```

### RFC classification

```
$ snetc classify 10.0.0.1 8.8.8.8 127.0.0.1

  Input                  Category          RFC          Routable
----------------------------------------------------------------
  10.0.0.1               Private           RFC 1918     no
  8.8.8.8                Public            -            yes
  127.0.0.1              Loopback          RFC 1122     no
```

### Interactive subnet planner

`snetc tree 10.0.0.0/16` opens a full-screen keyboard-driven planner. Split and join subnets, assign labels, undo/redo, then export to EDN, JSON, or YAML.

```
snetc tree: 10.0.0.0/16  (3 leaf subnets)

    #  Subnet              Mask               Range                          Hosts  Act  Lbl
--------------------------------------------------------------------------------------------
      1  10.0.0.0/17         255.255.128.0      10.0.0.0..10.0.127.255         32766  s    Web tier
      2  10.0.128.0/18       255.255.192.0      10.0.128.0..10.0.191.255       16382  s/j  App tier
>     3  10.0.192.0/18       255.255.192.0      10.0.192.0..10.0.255.255       16382  s/j  DB tier

up/down k/j  s split  J join  S split-to  H hosts  f filter  / search  : cmd  i import  e export  p print  q quit
```

| Key | Action |
|---|---|
| Arrow keys or `j`/`k` | Move selection |
| `g` / `G` | Jump to first / last visible row |
| `Page Up` / `Page Down` | Move by one visible page |
| `s` / Enter / Space | Split subnet |
| `J` / Backspace | Join with sibling |
| `S` | Split to a prompted prefix |
| `H` | Split for a prompted host count |
| `l` | Label subnet |
| `/` | Search by CIDR, IP, prefix, or label |
| `f` | Filter by CIDR, IP, prefix, or label |
| Escape | Clear filter |
| `u` / `r` | Undo / redo |
| `:` | Open command palette |
| `i` | Import EDN or JSON plan |
| `e` | Export to EDN, JSON, or YAML |
| `p` | Write leaf CIDRs to file |
| `q` / Ctrl-C | Quit |

Command palette examples:

```text
: s /26
: h 62
: j /24
: f /26
: export json plan.json
: import plan.json
: print-selected
```

---

## License

[MIT](https://github.com/pbsladek/snetc/blob/main/LICENSE) © 2026 Paul Sladek
