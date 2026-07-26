# fix: restructure bb-test-runner's reader-conditional to clear SL008

## Overview

`components/bb-test-runner/src/ai/miniforge/bb_test_runner/core.cljc`
defines `classpath-test-roots` as a whole top-level
`#?(:bb (defn- classpath-test-roots ...) :clj (defn- classpath-test-roots
...))` form, referenced by the same-file `run-all`. This is exactly the
shape `stratum-lint`'s `SL008` guard (added in
[stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15),
merged same day, pin bumped in miniforge PR
[#1515](https://github.com/miniforge-ai/miniforge/pull/1515)) exists to
catch: `--fix`'s `def-form?` never recognizes a `defn`/`defn-` nested
inside a reader-conditional splice, so a naive `--fix` pass would
silently relocate `classpath-test-roots` past `run-all` and break
compilation, the same way `create-datalevin-store` broke
`components/artifact/interface.cljc` before that Wave 1 PR (#1514)
restructured it.

SL008 now refuses instead of corrupting the file — but that turns the
first `--fix` pass over this component (whenever its own Wave 1 PR
lands) into a hard pre-commit block rather than a silent bug. Cheaper to
restructure now than to hit the block later.

Confirmed via direct testing, not just inspection, that this is the
*only* remaining at-risk file: grepped the tree for the same
`#?(:bb ... :clj ...)`-wrapped top-level `defn`/`defn-` shape and ran
plain lint + `--fix` (current pin) against every hit not already fixed.
`workflow/interface/resume.cljc` and
`event-stream/interface/manifest.cljc` both produced zero diff — their
wrapped defs have no same-file caller recognized outside another
wrapped form, so they're not at risk. `bb-test-runner/core.cljc` tripped
SL008 exactly as predicted. No new stratum-lint issue was filed:
stratum-lint#15's own PR body already names this exact file as a second
confirmed hit, so an issue would have duplicated a fix that's already
merged.

## Changes in Detail

`classpath-test-roots` restructured to match the convention
`components/datalevin/interface.cljc` and `components/artifact/interface.cljc`
already use: the `#?()` split now lives inside the function body instead
of wrapping the whole top-level form, and uses `:default` instead of
`:clj` (this codebase's `.cljc` reader-conditional convention — `:clj`
would leave the else branch invisible to some non-JVM-Clojure readers
that only understand `:default`).

```clojure
(defn- classpath-test-roots
  "Return the `/test` roots on the current Babashka classpath. Under
   JVM Clojure, `babashka.classpath` doesn't exist, so this throws an
   explicit unsupported-runtime error instead."
  []
  #?(:bb (->> (bb-classpath/split-classpath (bb-classpath/get-classpath))
              (filter #(str/ends-with? % "/test")))
     :default (throw (ex-info "Unsupported runtime: bb-test-runner run-all is only available under Babashka"
                              {:runtime :jvm
                               :namespace 'ai.miniforge.bb-test-runner.core}))))
```

Behavior is unchanged: same Babashka classpath lookup, same JVM-side
`ex-info` throw. Only the reader-conditional's syntactic position moved
by hand.

**Scope grew once staged, unavoidably.** `classpath-test-roots` was
previously invisible to `stratum-lint`'s reference graph (the whole
point of the SL008 bug). The moment it became a normal, recognized
`defn-`, pre-commit's `lint:stratum` autofix hook ran its one-time
full-file `--fix` pass over this file for the first time and added
`^{:stratum n}` metadata to every def in it (234/235 line diff) — this
is the documented, expected behavior on first touch of a not-yet-Waved
file, not something this PR chose to do.

That full pass also surfaced a genuine, pre-existing `SL003`: the file
uses 5 distinct layers against the 3-layer budget. This is unrelated to
the SL008 fix — it's this component's real Wave 1 finding, just hidden
until `--fix` could see the full reference graph. Splitting the
namespace to resolve it is out of scope for a fix that's supposed to be
about one reader-conditional; deferred to this component's own future
Wave 1 PR (`work/stratum-lint-baseline-2026-07-24.md`), same as
`components/artifact`'s `transit_store.clj`/`transit_store_test.clj`
findings were deferred in PR #1514. Committed with
`MINIFORGE_STRATUM_BUDGET_MODE=warn` to pass the pre-commit gate for
this one, already-known, already-deferred finding.

## Testing Plan

1. Loaded the namespace under both runtimes: `bb -e "(require
   'ai.miniforge.bb-test-runner.core)"` succeeds; under JVM Clojure
   (`clojure -Sdeps ... -M -e ...` with the component's paths/deps on
   the classpath), the namespace loads and calling
   `classpath-test-roots` throws the expected
   `Unsupported runtime: bb-test-runner run-all is only available under
   Babashka` `ex-info`, matching the pre-change JVM placeholder exactly.
2. `clj-kondo --lint components/bb-test-runner`: 0 errors, 0 warnings.
3. Plain (non-`--fix`) `stratum-lint` on the changed file: silent — no
   SL008, no other finding.
4. Ran the component's own test suite (`cognitect.test-runner` against
   `components/bb-test-runner/test`): 32 tests, 64 assertions, 0
   failures/errors.
5. Confirmed via direct experiment that a plain `--fix` pass over the
   whole file now succeeds (no more SL008) but also fully restratifies
   every def in the file — this happened for real at commit time via
   the `lint:stratum` pre-commit hook, not just in a manual test. Ran
   `--fix` a second time afterward: zero diff, confirms the committed
   state is a stable fixed point.
6. Re-ran `clj-kondo` and the component test suite (32 tests, 64
   assertions, 0 failures/errors) against the final, fully-restratified
   file actually being committed — not just the pre-hook minimal edit.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change: only the reader-conditional restructure and the mechanical
`^{:stratum n}` tagging that followed from it. The SL003 finding (5
layers, over budget) is deferred, not resolved — this component's own
Wave 1 namespace-split PR remains open work, tracked in
`work/stratum-lint-baseline-2026-07-24.md`.

## Related Issues/PRs

- Upstream fix: [stratum-lint#15](https://github.com/miniforge-ai/stratum-lint/pull/15)
  (SL008 guard, merged)
- Pin bump: miniforge [#1515](https://github.com/miniforge-ai/miniforge/pull/1515)
- Origin of the pattern: miniforge [#1514](https://github.com/miniforge-ai/miniforge/pull/1514)
  (`components/artifact` Wave 1, `interface.cljc` restructure)
- Baseline/wave tracking: `work/stratum-lint-baseline-2026-07-24.md`

## Checklist

- [x] Grepped the tree for other `#?(:bb ...)`-wrapped top-level
      `defn`/`defn-` forms; tested all remaining unresolved candidates
      directly against the current stratum-lint pin
- [x] Confirmed `workflow/interface/resume.cljc` and
      `event-stream/interface/manifest.cljc` are safe (zero diff under
      `--fix`), not just safe-by-inspection
- [x] Restructured `bb-test-runner/core.cljc`'s only at-risk def
- [x] Verified under both `bb` and JVM Clojure
- [x] `clj-kondo` clean
- [x] Plain lint clean (no SL008)
- [x] Component tests pass (32 tests, 64 assertions, 0 failures/errors)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Pre-existing SL003 (5 layers, over budget) documented and deferred
      to this component's own Wave 1 PR, same pattern as PR #1514's
      deferred `transit_store.clj` findings; committed with
      `MINIFORGE_STRATUM_BUDGET_MODE=warn` for this one known finding
