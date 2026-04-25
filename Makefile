.PHONY: help run run-dist exec tui test spec build native native-smoke bench bench-native clean container-build container-run container-push release changelog

JAR          := target/snetc-0.1.0.jar
BINARY       := dist/snetc
NATIVE_TMP   ?= target/native-tmp
GRAALVM_HOME ?=
NATIVE_IMAGE ?= $(shell \
	if [ -n "$(GRAALVM_HOME)" ] && [ -x "$(GRAALVM_HOME)/bin/native-image" ]; then \
		echo "$(GRAALVM_HOME)/bin/native-image"; \
	elif [ -n "$$JAVA_HOME" ] && [ -x "$$JAVA_HOME/bin/native-image" ]; then \
		echo "$$JAVA_HOME/bin/native-image"; \
	else \
		command -v native-image 2>/dev/null || echo native-image; \
	fi)
IMAGE        ?= pwbsladek/snetc
TAG          ?= dev
IMAGE_REF    = $(IMAGE):$(TAG)
CTR          ?= $(shell command -v podman 2>/dev/null || echo docker)
CIDR         ?= 10.0.0.0/24
BUILD_INPUTS := deps.edn build.clj $(shell find src -type f)

help:
	@printf '%s\n' \
		'snetc make targets:' \
		'' \
		'  make run ARGS="10.0.0.0/24"              Run with clojure -M' \
		'  make exec ARGS="10.0.0.0/24 --tree 26"   Build/run native binary' \
		'  make tui CIDR="10.0.0.0/24"              Run interactive TUI planner' \
		'  make test                                Run unit tests' \
		'  make spec                                Run generative spec tests' \
		'  make build                               Build uberjar' \
		'  make native                              Build GraalVM native binary' \
		'  make native-smoke                        Build and smoke-test native binary' \
		'  make bench                               Run JVM benchmark probes' \
		'  make bench-native                        Run native benchmark probes' \
		'  make container-build TAG=tagname          Build Docker image' \
		'  make container-run TAG=tagname ARGS=...   Run Docker image' \
		'  make container-push TAG=tagname           Push Docker image' \
		'  make changelog                           Regenerate CHANGELOG.md' \
		'  make release                             Bump patch tag and push' \
		'  make clean                               Remove build outputs' \
		'' \
		'Variables:' \
		'  IMAGE=pwbsladek/snetc TAG=dev CTR=docker GRAALVM_HOME=/path/to/graalvm'

run:
	clojure -M -m snetc.core $(ARGS)

run-dist: $(BINARY)
	$(BINARY) $(ARGS)

# Convenience: forwards all extra arguments to the native binary.
# Usage: make exec ARGS="192.168.0.0/22 --tree 24"
#   or just run dist/snetc directly.
exec: $(BINARY)
	$(BINARY) $(ARGS)

tui: $(BINARY)
	$(BINARY) tree $(CIDR)

test:
	bin/kaocha

spec:
	bin/kaocha --focus snetc.spec-test

build:
	clojure -T:build uber

$(JAR): $(BUILD_INPUTS)
	clojure -T:build uber

native: $(BINARY)

native-smoke: $(BINARY)
	bin/smoke-native $(BINARY)

bench:
	clojure -M -m snetc.perf-bench

bench-native: $(BINARY)
	bin/bench-native $(BINARY)

$(BINARY): $(JAR) Makefile bin/smoke-native bin/bench-native
	mkdir -p dist $(NATIVE_TMP)
	$(NATIVE_IMAGE) \
		-jar $(JAR) \
		-o $(BINARY) \
		-J-Djava.io.tmpdir=$(abspath $(NATIVE_TMP)) \
		--initialize-at-build-time \
		--no-fallback \
		-H:+ReportExceptionStackTraces

container-build:
	$(CTR) build -f Containerfile -t $(IMAGE_REF) .

# Usage: make container-run ARGS="classify 10.0.0.1 8.8.8.8"
container-run:
	$(CTR) run --rm $(IMAGE_REF) $(ARGS)

container-push:
	$(CTR) push $(IMAGE_REF)

clean:
	clojure -T:build clean
	rm -rf dist

# Regenerate CHANGELOG.md from git history using git-cliff (https://github.com/orhun/git-cliff).
# Install: cargo install git-cliff  or  brew install git-cliff
changelog:
	git-cliff -o CHANGELOG.md

# Bump the patch version of the latest tag (e.g. v0.1.2 → v0.1.3) and push to trigger CI release.
# If no tags exist, starts at v0.1.0.
release:
	$(eval LATEST := $(shell git tag -l 'v*' | sort -V | tail -1))
	$(eval TAG := $(if $(LATEST), \
		v$(shell echo $(LATEST) | sed 's/^v//' | awk -F. '{print $$1"."$$2"."$$3+1}'), \
		v0.1.0))
	@echo "Tagging $(TAG)"
	git tag $(TAG)
	git push origin $(TAG)
