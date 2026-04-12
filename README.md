# snetc

IPv4 subnet calculator for the command line.

## Install

**Requirements:** [Clojure](https://clojure.org/guides/install_clojure) or a [GraalVM](https://www.graalvm.org/) JDK for native builds.

```sh
# Run without building
make run ARGS="192.168.0.0/22"

# Build native binary → dist/snetc
make native
```

## Usage

```
snetc <cidr>                              Show info for a subnet
snetc <cidr> --split <prefix>             List all /<prefix> subnets within <cidr>
snetc <cidr> --tree  <prefix>             Show split tree down to /<prefix>
snetc aggregate <cidr> [<cidr> ...]       Aggregate CIDRs to minimal covering set
snetc aggregate                           Read CIDRs from stdin (one per line)
snetc contains <cidr> <ip> [<ip> ...]     Check which IPs fall within a subnet
snetc free <parent> <alloc> [...]         Show unallocated space in a subnet
snetc plan <parent> <hosts> [<hosts> ...] VLSM: allocate subnets by host count
snetc overlaps <cidr> [<cidr> ...]        Detect overlapping/contained networks
snetc lpm <cidr|ip> ...                   Longest-prefix match (CIDRs=routes, IPs=lookups)
snetc diff <cidr> ... -- <cidr> ...       Diff two sets of CIDRs
snetc classify <ip-or-cidr> ...           RFC classification of IPs/CIDRs
snetc range <start-ip> <end-ip|+count>    Convert IP range to minimal CIDRs
snetc tree <cidr>                         Interactive split/join subnet planner
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

```
$ snetc 192.168.0.0/22 --split 24
  Network/CIDR         Subnet Mask        First Host         Last Host          Broadcast          Hosts
--------------------------------------------------------------------------------------------------------------
  192.168.0.0/24       255.255.255.0      192.168.0.1        192.168.0.254      192.168.0.255      254
  192.168.1.0/24       255.255.255.0      192.168.1.1        192.168.1.254      192.168.1.255      254
  192.168.2.0/24       255.255.255.0      192.168.2.1        192.168.2.254      192.168.2.255      254
  192.168.3.0/24       255.255.255.0      192.168.3.1        192.168.3.254      192.168.3.255      254
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

Opens a keyboard-driven terminal planner for manually splitting and joining subnets. Use arrow keys or `j`/`k` to select a visible subnet, `s` or Enter to split it, `J` or Backspace to join sibling leaves, `l` to label a subnet, `u`/`r` for undo/redo, `e` to export the plan to `snetc-plan.edn`, `p` to write leaf CIDRs to `snetc-leaves.txt`, and `q` to quit. The existing static tree remains available as `snetc <cidr> --tree <prefix>`.

## Development

```sh
make test     # run tests
make spec     # run generative spec tests
make build    # build uberjar → target/snetc-0.1.0.jar
make native   # compile native binary → dist/snetc
make clean    # remove build artifacts
```

## License

MIT
