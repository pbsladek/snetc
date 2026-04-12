.PHONY: run run-dist test spec build native clean container-build container-run release changelog

JAR          := target/snetc-0.1.0.jar
BINARY       := dist/snetc
GRAALVM_HOME ?= /Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
NATIVE_IMAGE ?= $(GRAALVM_HOME)/bin/native-image
IMAGE        ?= snetc
CTR          ?= $(shell command -v podman 2>/dev/null || echo docker)
BUILD_INPUTS := deps.edn build.clj $(shell find src -type f)

run:
	clojure -M -m snetc.core $(ARGS)

run-dist: $(BINARY)
	$(BINARY) $(ARGS)

# Convenience: forwards all extra arguments to the native binary.
# Usage: make exec ARGS="192.168.0.0/22 --tree 24"
#   or just run dist/snetc directly.
exec: $(BINARY)
	$(BINARY) $(ARGS)

test:
	bin/kaocha

spec:
	bin/kaocha --focus snetc.spec-test

build:
	clojure -T:build uber

$(JAR): $(BUILD_INPUTS)
	clojure -T:build uber

native: $(BINARY)

$(BINARY): $(JAR)
	mkdir -p dist
	$(NATIVE_IMAGE) \
		-jar $(JAR) \
		-o $(BINARY) \
		--initialize-at-build-time \
		--no-fallback \
		-H:+ReportExceptionStackTraces

container-build:
	$(CTR) build -f Containerfile -t $(IMAGE) .

# Usage: make container-run ARGS="classify 10.0.0.1 8.8.8.8"
container-run:
	$(CTR) run --rm $(IMAGE) $(ARGS)

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
