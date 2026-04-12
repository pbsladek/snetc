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
```

## Examples

```sh
snetc 192.168.0.0/22
snetc 192.168.0.0/22 --split 24
snetc 192.168.0.0/22 --tree 24
snetc aggregate 10.0.0.0/24 10.0.1.0/24
snetc contains 192.168.0.0/22 192.168.1.1 10.0.0.1
snetc free 192.168.0.0/22 192.168.0.0/24 192.168.2.0/23
snetc plan 192.168.0.0/22 500 200 50 10
snetc overlaps 10.0.0.0/8 10.0.0.0/24 192.168.0.0/16
snetc lpm 10.0.0.0/8 10.0.0.0/24 0.0.0.0/0 10.0.0.50 8.8.8.8
snetc diff 10.0.0.0/24 10.0.1.0/24 -- 10.0.0.0/23 10.0.2.0/24
snetc classify 10.0.0.1 192.168.1.1 8.8.8.8 127.0.0.1
snetc range 10.0.0.5 10.0.1.200
snetc range 10.0.0.0 +1000
```

## Development

```sh
make test     # run tests
make build    # build uberjar → target/snetc-0.1.0.jar
make native   # compile native binary → dist/snetc
make clean    # remove build artifacts
```

## License

MIT
