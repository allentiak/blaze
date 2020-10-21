VERSION := "0.9.0-alpha.25"
MODULES := $(wildcard modules/*)

$(MODULES):
	$(MAKE) -C $@ $(MAKECMDGOALS)

lint-root:
	clj-kondo --lint src test

lint: $(MODULES) lint-root

test-root:
	clojure -M:test --profile :ci

test: $(MODULES)

test-coverage: $(MODULES)

uberjar:
	clojure -Sforce -A:depstar -m hf.depstar.uberjar target/blaze-${VERSION}-standalone.jar

.PHONY: $(MODULES) lint-root lint test-root test test-coverage uberjar
