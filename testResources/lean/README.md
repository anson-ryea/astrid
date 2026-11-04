# Lean test environment

This pinned Lake project is used only by ASTRID's synthetic integration tests.
It has no curated benchmark sources and does not use any external dataset
checkout. Test tasks, contexts and private contracts come from the adjacent
`dataset/simple`, `evaluation/simple` and `private/simple` fixture directories.

Quick tests copy these configuration files to temporary directories with an
empty dependency cache and mock all Lean execution. They need neither Lean nor
downloaded Mathlib packages:

```sh
./kotlin test --exclude-tag=lean-integration
```

For native integration tests, install the pinned Lean toolchain, prepare this
test environment's own dependency cache, then run the tests from the repository root:

```sh
mkdir -p build/test-lean
cp testResources/lean/lean-toolchain testResources/lean/lakefile.toml testResources/lean/lake-manifest.json build/test-lean/
(cd build/test-lean && lake exe cache get)
./kotlin test --include-tag=lean-integration
```

`./kotlin test` runs both suites. Configuration fixtures stay in `testResources/`;
generated environments and dependency caches stay under the ignored `build/` directory.
