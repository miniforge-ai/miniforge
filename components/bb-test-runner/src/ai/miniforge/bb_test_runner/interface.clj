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
  "Test runner for Babashka tasks. Pass-through to `core`.

   Intended runtime: Babashka. `run-all` discovers `*_test.clj` files on
   the bb classpath's `/test` roots. Under JVM Clojure the discovery
   path won't resolve (`babashka.classpath` is Babashka-only), so tests
   of bb-utils components themselves run under JVM via the pure helpers
   (`path->ns-symbol`, `discover-test-namespaces`) + the standard
   cognitect test-runner. Coverage execution shells out to a JVM
   Cloverage process using the repo's deps.edn."
  (:require [ai.miniforge.bb-test-runner.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn run-all
  "Discover and run every `*_test.clj` file under each `/test` root on
   the current Babashka classpath. Exits non-zero via `System/exit` if
   any test fails or errors."
  []
  (core/run-all))

(defn discover-test-namespaces
  "Pure helper: given a seq of `/test` roots on the classpath, return a
   seq of `{:file :ns}` maps. Exposed so callers can inspect discovery
   without actually requiring/running anything."
  [roots]
  (core/discover-test-namespaces roots))

(defn path->ns-symbol
  "Pure helper: convert a `*_test.clj` file path (relative to a
   classpath `/test` root) into its namespace symbol."
  [relative-path]
  (core/path->ns-symbol relative-path))

(defn stable-tag-globs
  "Return the stable-tag glob patterns recognized by Miniforge's
   stable-derived test scope."
  []
  (core/stable-tag-globs))

(defn stable-tags-present?
  "True when `tags` contains at least one recognized stable tag."
  [tags]
  (core/stable-tags-present? tags))

(defn parse-project-selector
  "Parse a Polylith project selector or env value into project names."
  [selector]
  (core/parse-project-selector selector))

(defn format-project-selector
  "Render an explicit Polylith project selector from project names."
  [projects]
  (core/format-project-selector projects))

(defn changed-projects-command
  "Return the argv that asks Polylith for changed-or-affected projects."
  []
  (core/changed-projects-command))

(defn parse-project-list-output
  "Parse a changed-or-affected project list response into project names."
  [output]
  (core/parse-project-list-output output))

(defn sanitize-git-worktree-env
  "Remove git worktree/index variables that must not leak into child
   processes spawned from git hook contexts."
  [env]
  (core/sanitize-git-worktree-env env))

(defn heartbeat-seconds
  "Return the configured heartbeat interval in seconds for long-running
   test commands."
  [env]
  (core/heartbeat-seconds env))

(defn parse-diagnostic-args
  "Parse supported stable-derived diagnostic CLI arguments."
  [args]
  (core/parse-diagnostic-args args))

(defn order-projects
  "Apply diagnostic ordering controls to a project vector."
  [projects opts]
  (core/order-projects projects opts))

(defn expand-project-groups
  "Return additive project groups that double in size until full scope."
  [projects start-size]
  (core/expand-project-groups projects start-size))

(defn bisect-project-groups
  "Return contiguous project groups in breadth-first binary partition order."
  [projects]
  (core/bisect-project-groups projects))

(defn diagnostic-test-plan
  "Return a stable-derived diagnostic plan over an explicit project set."
  [opts]
  (core/diagnostic-test-plan opts))

(defn classify-coverage-paths
  "Pure helper: split merged classpath paths into source and test roots
   suitable for Cloverage."
  [paths]
  (core/classify-coverage-paths paths))

(defn coverage-args
  "Pure helper: build the Cloverage argv for a repo deps config."
  [deps-config opts]
  (core/coverage-args deps-config opts))

(defn load-deps-config
  "Load the repo deps.edn config needed for coverage planning."
  [repo-root]
  (core/load-deps-config repo-root))

(defn coverage-install-args
  "Pure helper: build the argv needed to prefetch the coverage tool."
  []
  (core/coverage-install-args))

(defn install-coverage-tool
  "Prefetch the Cloverage dependency into the repo's local Clojure cache.
   Accepts `{:repo-root \".\"}` and returns the process exit code."
  [opts]
  (core/install-coverage-tool opts))

(defn run-coverage
  "Run Cloverage for the repo rooted at `:repo-root` using the selected
   deps.edn alias. Options:

   - `:repo-root`       repo path, default `.`
   - `:alias-key`       single deps alias keyword
   - `:alias-keys`      vector of deps alias keywords, default `[:test]`
   - `:output-dir`      coverage output dir, default `target/coverage`
   - `:fail-threshold`  integer percentage, default `0`

   Returns the process exit code."
  [opts]
  (core/run-coverage opts))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (path->ns-symbol "ai/miniforge/bb_paths/core_test.clj")
  (classify-coverage-paths ["tasks" "test" "mvp/src"])

  :leave-this-here)
