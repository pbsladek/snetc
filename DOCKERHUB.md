# snetc

Short description:

```text
Native IPv4 subnet calculator CLI for CIDR, VLSM, routing, and RFC range checks.
```

## About this image

`pwbsladek/snetc` packages `snetc`, a native IPv4 subnet calculator CLI. It can inspect CIDR blocks, split networks, aggregate routes, calculate free space, plan VLSM allocations, detect overlaps, run longest-prefix-match lookups, classify RFC ranges, and convert IP ranges to minimal CIDRs.

The image builds `snetc` as a GraalVM native executable and runs it from a Docker Hardened Images static runtime.

## Quick start

Show subnet information:

```sh
docker run --rm pwbsladek/snetc:latest 192.168.0.0/22
```

Classify addresses:

```sh
docker run --rm pwbsladek/snetc:latest classify 10.0.0.1 8.8.8.8
```

Aggregate adjacent CIDRs:

```sh
docker run --rm pwbsladek/snetc:latest aggregate 10.0.0.0/24 10.0.1.0/24
```

Read CIDRs from standard input:

```sh
printf "10.0.0.0/24\n10.0.1.0/24\n" | docker run --rm -i pwbsladek/snetc:latest aggregate
```

Print help:

```sh
docker run --rm pwbsladek/snetc:latest --help
```

Run the interactive TUI planner:

```sh
docker run --rm -it pwbsladek/snetc:latest-tui tree 10.0.0.0/24
```

The default `latest` image is distroless-style and is intended for non-interactive commands. The `latest-tui` image is a TUI-capable Docker Hardened Images Debian Base variant with the shell and coreutils needed by the terminal UI.

## Common commands

```text
snetc <cidr>                              Show info for a subnet
snetc <cidr> --split <prefix>             List all /<prefix> subnets within <cidr>
snetc aggregate <cidr> [<cidr> ...]       Aggregate CIDRs to minimal covering set
snetc contains <cidr> <ip> [<ip> ...]     Check which IPs fall within a subnet
snetc free <parent> <alloc> [...]         Show unallocated space in a subnet
snetc plan <parent> <hosts> [<hosts> ...] VLSM allocation by host count
snetc overlaps <cidr> [<cidr> ...]        Detect overlapping or contained networks
snetc lpm <cidr|ip> ...                   Longest-prefix match
snetc diff <cidr> ... -- <cidr> ...       Diff two CIDR sets
snetc classify <ip-or-cidr> ...           RFC classification of IPs and CIDRs
snetc range <start-ip> <end-ip|+count>    Convert IP range to minimal CIDRs
```

## Tags

Use `latest` for the current release:

```sh
docker pull pwbsladek/snetc:latest
```

Release tags are also published in two forms:

```text
pwbsladek/snetc:v0.1.9
pwbsladek/snetc:0.1.9
pwbsladek/snetc:v0.1.9-tui
pwbsladek/snetc:0.1.9-tui
pwbsladek/snetc:latest-tui
```

## Platforms

Published images are multi-architecture:

```text
linux/amd64
linux/arm64
```

## Source and build

Source repository:

```text
https://github.com/pbsladek/snetc
```

The container build uses:

```text
GraalVM native-image for the snetc executable
Docker Hardened Images static runtime for the final image
```

## License

MIT License. See the source repository license for full terms.
