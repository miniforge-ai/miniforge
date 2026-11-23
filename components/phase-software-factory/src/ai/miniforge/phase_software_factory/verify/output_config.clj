;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify.output-config
  "Test-output formats and evidence display bounds.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} verify-error-preview-limit
  "Maximum test-runner output included in the verify phase error message."
  2000)

(def ^{:stratum 0} truncated-output-suffix
  "Suffix appended when test-runner output is truncated for display."
  "\n...")

(def ^{:stratum 0} ran-tests-pattern
  "clojure.test's per-namespace count line. A Polylith run prints one per
   namespace; the run's totals are the SUM over every match, never the
   first match alone (series 8, 2026-09-04: the first namespace's
   `Ran 3 tests` stood in for a 911-namespace run)."
  #"Ran (\d+) tests? containing (\d+) assertions?")

(def ^{:stratum 0} namespace-summary-pattern
  "clojure.test's per-namespace `F failures, E errors.` line, anchored to
   the start of a line so the runner's per-project roll-up
   (`Test results: N passes, F failures, E errors.`), which repeats the
   same numbers, is not counted a second time."
  #"(?m)^(\d+) failures?,\s*(\d+) errors?")

(def ^{:stratum 0} failure-header-pattern
  "One clojure.test failure header: `FAIL in (test-name) (file:line)` or
   `ERROR in (test-name) (file:line)`. The location group is optional —
   a fixture that throws before any test runs prints none."
  #"^(FAIL|ERROR) in \(([^)]*)\)(?: \(([^)]*)\))?")

(def ^{:stratum 0} max-failure-blocks
  "Failure blocks kept per run. A compile error can fail every test in a
   namespace; twenty shows the pattern without flooding the implementer
   prompt."
  20)

(def ^{:stratum 0} max-failure-block-lines
  "Detail lines kept under one failure header — the `expected:`/`actual:`
   pair plus a short stack is enough to act on."
  12)

(def ^{:stratum 0} max-named-failures
  "Failing test names spelled out in the verify summary; any beyond this
   are counted, not listed."
  5)

(comment ::test-output-contract)
