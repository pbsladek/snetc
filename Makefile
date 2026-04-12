.PHONY: run run-dist test spec build native clean container-build container-run

JAR          := target/snetc-0.1.0.jar
BINARY       := dist/snetc
GRAALVM_HOME ?= /Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
NATIVE_IMAGE ?= $(GRAALVM_HOME)/bin/native-image
IMAGE        ?= snetc
CTR          ?= $(shell command -v podman 2>/dev/null || echo docker)

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

build: $(JAR)

$(JAR):
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
