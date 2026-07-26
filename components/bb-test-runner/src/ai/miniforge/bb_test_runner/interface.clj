;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.bb-test-runner.interface
  "Test runner for Babashka tasks. Pass-through to this component's
   implementation namespaces (`test-discovery`, `stable-derived`,
   `diagnostic-plan`, `coverage-paths`, `coverage-cmd`, `coverage-exec`
   — see `work/stratum-lint-baseline-2026-07-24.md` for why the
   formerly-single `core` namespace was split into these).

   Intended runtime: Babashka. `run-all` discovers `*_test.clj` files on
   the bb classpath's `/test` roots. Under JVM Clojure the discovery
   path won't resolve (`babashka.classpath` is Babashka-only), so tests
   of bb-utils components themselves run under JVM via the pure helpers
   (`path->ns-symbol`, `discover-test-namespaces`) + the standard
   cognitect test-runner. Coverage execution shells out to a JVM
   Cloverage process using the repo's deps.edn."
  (:require [ai.miniforge.bb-test-runner.coverage-cmd :as coverage-cmd]
            [ai.miniforge.bb-test-runner.coverage-exec :as coverage-exec]
            [ai.miniforge.bb-test-runner.coverage-paths :as coverage-paths]
            [ai.miniforge.bb-test-runner.diagnostic-plan :as diagnostic-plan]
            [ai.miniforge.bb-test-runner.stable-derived :as stable-derived]
            [ai.miniforge.bb-test-runner.test-discovery :as test-discovery]))

;------------------------------------------------------------------------------ Layer 0

;; Public API — pass-through only
(defn ^{:stratum 0} run-all
  "Discover and run every `*_test.clj` file under each `/test` root on
   the current Babashka classpath. Exits non-zero via `System/exit` if
   any test fails or errors."
  []
  (test-discovery/run-all))

(defn ^{:stratum 0} discover-test-namespaces
  "Pure helper: given a seq of `/test` roots on the classpath, return a
   seq of `{:file :ns}` maps. Exposed so callers can inspect discovery
   without actually requiring/running anything."
  [roots]
  (test-discovery/discover-test-namespaces roots))

(defn ^{:stratum 0} path->ns-symbol
  "Pure helper: convert a `*_test.clj` file path (relative to a
   classpath `/test` root) into its namespace symbol."
  [relative-path]
  (test-discovery/path->ns-symbol relative-path))

(defn ^{:stratum 0} stable-tag-globs
  "Return the stable-tag glob patterns recognized by Miniforge's
   stable-derived test scope."
  []
  (stable-derived/stable-tag-globs))

(defn ^{:stratum 0} stable-tags-present?
  "True when `tags` contains at least one recognized stable tag."
  [tags]
  (stable-derived/stable-tags-present? tags))

(defn ^{:stratum 0} parse-project-selector
  "Parse a Polylith project selector or env value into project names."
  [selector]
  (stable-derived/parse-project-selector selector))

(defn ^{:stratum 0} format-project-selector
  "Render an explicit Polylith project selector from project names."
  [projects]
  (stable-derived/format-project-selector projects))

(defn ^{:stratum 0} changed-projects-command
  "Return the argv that asks Polylith for changed-or-affected projects."
  []
  (stable-derived/changed-projects-command))

(defn ^{:stratum 0} changed-projects-since-stable-command
  "Return the argv that asks Polylith for changed-or-affected projects
   relative to the current stable tag anchor."
  []
  (stable-derived/changed-projects-since-stable-command))

(defn ^{:stratum 0} parse-project-list-output
  "Parse a changed-or-affected project list response into project names.

   Returns a vector on success, or `{:ok? false :error ...}` on invalid input."
  [output]
  (stable-derived/parse-project-list-output output))

(defn ^{:stratum 0} sanitize-git-worktree-env
  "Remove git worktree/index variables that must not leak into child
   processes spawned from git hook contexts."
  [env]
  (stable-derived/sanitize-git-worktree-env env))

(defn ^{:stratum 0} heartbeat-seconds
  "Return the configured heartbeat interval in seconds for long-running
   test commands."
  [env]
  (stable-derived/heartbeat-seconds env))

(defn ^{:stratum 0} parse-diagnostic-args
  "Parse supported stable-derived diagnostic CLI arguments."
  [args]
  (diagnostic-plan/parse-diagnostic-args args))

(defn ^{:stratum 0} order-projects
  "Apply diagnostic ordering controls to a project vector."
  [projects opts]
  (stable-derived/order-projects projects opts))

(defn ^{:stratum 0} expand-project-groups
  "Return additive project groups that double in size until full scope."
  [projects start-size]
  (stable-derived/expand-project-groups projects start-size))

(defn ^{:stratum 0} bisect-project-groups
  "Return contiguous project groups in breadth-first binary partition order."
  [projects]
  (stable-derived/bisect-project-groups projects))

(defn ^{:stratum 0} diagnostic-test-plan
  "Return a stable-derived diagnostic plan over an explicit project set."
  [opts]
  (diagnostic-plan/diagnostic-test-plan opts))

(defn ^{:stratum 0} classify-coverage-paths
  "Pure helper: split merged classpath paths into source and test roots
   suitable for Cloverage."
  [paths]
  (coverage-paths/classify-coverage-paths paths))

(defn ^{:stratum 0} coverage-args
  "Pure helper: build the Cloverage argv for a repo deps config."
  [deps-config opts]
  (coverage-cmd/coverage-args deps-config opts))

(defn ^{:stratum 0} load-deps-config
  "Load the repo deps.edn config needed for coverage planning."
  [repo-root]
  (coverage-exec/load-deps-config repo-root))

(defn ^{:stratum 0} coverage-install-args
  "Pure helper: build the argv needed to prefetch the coverage tool."
  []
  (coverage-cmd/coverage-install-args))

(defn ^{:stratum 0} install-coverage-tool
  "Prefetch the Cloverage dependency into the repo's local Clojure cache.
   Accepts `{:repo-root \".\"}` and returns the process exit code."
  [opts]
  (coverage-exec/install-coverage-tool opts))

(defn ^{:stratum 0} run-coverage
  "Run Cloverage for the repo rooted at `:repo-root` using the selected
   deps.edn alias. Options:

   - `:repo-root`       repo path, default `.`
   - `:alias-key`       single deps alias keyword
   - `:alias-keys`      vector of deps alias keywords, default `[:test]`
   - `:output-dir`      coverage output dir, default `target/coverage`
   - `:fail-threshold`  integer percentage, default `0`

   Returns the process exit code."
  [opts]
  (coverage-exec/run-coverage opts))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (path->ns-symbol "ai/miniforge/bb_paths/core_test.clj")
  (classify-coverage-paths ["tasks" "test" "mvp/src"])

  :leave-this-here)
