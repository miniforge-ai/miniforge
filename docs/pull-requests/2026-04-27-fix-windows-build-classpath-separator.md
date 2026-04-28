# fix/windows-build-failure

## Summary

Fixes the remaining Windows release-build failure after the `clojure.exe`
shim landed.

The root problem was that the root `build.clj` script was assembling Babashka
classpath arguments with a hard-coded `:` separator. That works on Unix, but it
breaks on Windows where the classpath separator is `;` and drive letters already
contain `:`.

This PR also switches the build-script Babashka subprocess calls from
throwing `bp/shell` to captured `bp/sh`, so non-zero exits produce stable
stdout/stderr diagnostics instead of the broken `clojure.pprint` multimethod
error that was obscuring the real failure.

## Key Changes

- added platform-aware classpath helpers in
  [build.clj](/tmp/mf-fix-windows-build-failure/build.clj)
- updated `bb-compatible?`, `bb-uberscript`, and `bb-uberjar` to use the
  platform-correct classpath string
- switched those subprocess paths to captured command execution so non-zero
  exits are reported cleanly
- added focused coverage for the classpath-join helper in
  [build_test.clj](/tmp/mf-fix-windows-build-failure/development/test/build_test.clj)

## Validation

- `clj-kondo --lint build.clj development/test/build_test.clj`
- `clojure -M:build:dev:test -e "(require 'clojure.test 'build-test)
  (let [r (clojure.test/run-tests 'build-test)]
  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))"`
- `bb build:cli`
- `bb pre-commit`
