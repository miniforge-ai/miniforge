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
(ns test-runner
  (:require
   [ai.miniforge.bb-proc.interface :as proc]
   [ai.miniforge.bb-test-runner.interface :as bb-test-runner]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-stream!
  "Start a test process with inherited stdio and return its exit code.

   The environment — `:env` in a leading opts map when it holds one, else
   this process's own — goes through `sanitize-git-worktree-env` on every
   call. A git hook exports `GIT_DIR`, and a test that inherits it runs
   `git init` and `git config` against the hook's repository instead of
   its temp directory."
  [& args]
  (let [[opts cmd-args] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])
        launch-env (into {} (System/getenv))
        env (bb-test-runner/sanitize-git-worktree-env
             (or (get opts :env) launch-env))
        {:keys [exit]} (deref (apply p/process
                                     (merge {:out :inherit :err :inherit}
                                            opts
                                            {:env env})
                                     cmd-args))]
    exit))

(def ^{:stratum 0} ^:private precommit-smoke-config-path
  "Working-directory-relative filesystem path to the smoke-set config.
   Not loaded via `io/resource` because bb tasks already run from the
   repo root by convention; if that ever changes the `(.isFile f)` guard
   surfaces the misconfiguration loudly."
  "resources/precommit-smoke-tests.edn")

(def ^{:stratum 0} ^:private project-test-roots
  "Directories, relative to projects/<name>, whose *_test.clj files
   `bb test:integration` runs. Discovered on disk rather than listed by
   hand: a hand list silently left new integration tests unrun.
   projects/miniforge/e2e is left out on purpose — it needs a real claude
   CLI or Docker (see its README)."
  {"miniforge"      ["test" "integration"]
   "miniforge-core" ["test"]})

(def ^{:stratum 0} ^:private development-test-root
  "Task and tooling tests (tests for tasks/*.clj, build.clj, the dev
   harness). `poly test :all` skips the development project, so
   `development` is their only runner."
  "development/test")

(def ^{:stratum 0} ^:private external-tests-env
  "Environment variable that, set to \"true\", makes `development` also
   run the tests tagged ^:external. Those call a real external backend
   (the claude CLI) and may send billable requests, so they are opt-in."
  "MINIFORGE_TEST_EXTERNAL")

(defn ^{:stratum 0} external-test-skip-form
  "Clojure source for the test JVM, evaluated after `nses` load: removes
   :test from every var tagged ^:external in them, so
   clojure.test/run-tests skips it, and prints each one skipped."
  [nses]
  (str "(doseq [v (mapcat (comp vals ns-interns) ["
       (apply str (map (fn [n] (str " '" n)) nses))
       "]) :when (:external (meta v))] "
       "(println \"Skipping external-backend test:\" (symbol v)) "
       "(alter-meta! v dissoc :test))"))

(defn- ^{:stratum 0} project-test-alias-flag
  "The -M flag selecting whichever of the project's test aliases exist."
  [deps-edn]
  (let [aliases (:aliases deps-edn)]
    (apply str "-M" (filter #(contains? aliases %) [:test :integration]))))

(defn- ^{:stratum 0} valid-namespace-token?
  "Conservative check: namespace entries in the EDN must look like
   period-separated, dash-segmented symbol tokens. Rejects strings with
   whitespace, quotes, comment chars, or any non-symbol character so a
   typo in the config doesn't silently break the generated -e form."
  [s]
  (and (string? s)
       (re-matches
        #"[a-zA-Z][a-zA-Z0-9_-]*[?!]?(\.[a-zA-Z][a-zA-Z0-9_-]*[?!]?)*"
        s)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} discovered-project-tests
  "Every *_test.clj namespace under `project`'s test roots, as sorted
   strings."
  [project]
  (->> (get project-test-roots project)
       (map #(str "projects/" project "/" %))
       bb-test-runner/discover-test-namespaces
       (map (comp str :ns))
       sort
       vec))

(defn ^{:stratum 1} discovered-development-tests
  "Every *_test.clj namespace under development/test, as sorted strings."
  []
  (->> (bb-test-runner/discover-test-namespaces [development-test-root])
       (map (comp str :ns))
       sort
       vec))

(defn- ^{:stratum 1} run-project-tests!
  [project test-nses]
  (let [deps (slurp (str "projects/" project "/deps.edn"))
        test-mode (project-test-alias-flag (edn/read-string deps))
        clojure-cmd (proc/clojure-command)
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(let [r (clojure.test/run-tests" run-expr ")] "
                  "  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))")]
    ;; :dir makes the project's deps.edn the CLI's project config. No
    ;; -Sdeps: CLI 1.12.x reads an -Sdeps value that does not start with
    ;; `{` as a file path, and this deps.edn opens with a comment.
    (run-stream! {:dir (str "projects/" project)}
                 clojure-cmd test-mode "-e" expr)))

(defn ^{:stratum 1} poly-all
  "Run every brick and project test through Polylith, serially.

   `:all`, not plain `poly test`: the plain form tests only bricks changed
   since the stable tag, so a test namespace that is out of scope and no
   longer compiles — or that a change breaks through a reverse dependency
   — is never loaded and cannot fail. Use `bb test` for fast local runs."
  []
  (println "🧪 Running tests via poly test :all...")
  (println "Command: clojure -M:poly test :all")
  (let [exit (run-stream! (proc/clojure-command) "-M:poly" "test" ":all")]
    (when-not (zero? exit)
      (println "❌ Tests failed with exit code:" exit)
      (System/exit exit))))

(defn ^{:stratum 1} conformance []
  (println "🧪 Running N1 conformance tests...")
  (let [test-nses ["conformance.n1_architecture_test"
                   "conformance.event_stream_test"
                   "conformance.agent_context_handoff_test"
                   "conformance.protocol_conformance_test"
                   "conformance.gate_enforcement_test"]
        clojure-cmd (proc/clojure-command)
        require-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        run-expr (apply str (map (fn [ns] (str " '" ns)) test-nses))
        expr (str "(require 'clojure.test" require-expr ") "
                  "(clojure.test/run-tests" run-expr ")")
        exit (run-stream! clojure-cmd "-M:conformance" "-e" expr)]
    (when-not (zero? exit)
      (println "❌ Conformance tests failed with exit code:" exit)
      (System/exit exit))))

(defn- ^{:stratum 1} read-precommit-smoke-nses!
  []
  (let [f (io/file precommit-smoke-config-path)]
    (when-not (.isFile f)
      (println "❌ Pre-commit smoke config not found:" precommit-smoke-config-path)
      (System/exit 1))
    (let [{:keys [smoke/namespaces]} (edn/read-string (slurp f))]
      (when (empty? namespaces)
        (println "❌ Pre-commit smoke config has no :smoke/namespaces")
        (System/exit 1))
      (when-let [bad (seq (remove valid-namespace-token? namespaces))]
        (println "❌ Pre-commit smoke config has invalid namespace tokens:"
                 (pr-str bad))
        (System/exit 1))
      namespaces)))

(defn ^{:stratum 1} graalvm []
  (println "🧪 Testing GraalVM/Babashka compatibility...")
  (let [clojure-cmd (proc/clojure-command)
        ;; Use :dev alias to get full component classpath
        env (bb-test-runner/sanitize-git-worktree-env (into {} (System/getenv)))
        cp (-> (p/sh {:out :string :env env} clojure-cmd "-A:dev" "-Spath")
               :out
               str/trim)
        ;; Add tests directory to classpath
        full-cp (str cp java.io.File/pathSeparator "tests")
        expr "(require 'graalvm-compatibility-test) (graalvm-compatibility-test/-main)"
        exit (run-stream! "bb" "-cp" full-cp "-e" expr)]
    (when-not (zero? exit)
      (println "❌ GraalVM compatibility tests failed with exit code:" exit)
      (System/exit exit))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} integration
  "Run every discovered project integration test (see
   `project-test-roots`)."
  []
  (println "🧪 Running project integration tests...")
  (doseq [project (sort (keys project-test-roots))]
    (let [test-nses (discovered-project-tests project)]
      (when (empty? test-nses)
        (println "❌ No integration tests discovered in project:" project)
        (System/exit 1))
      (println (format "Running %d integration test namespaces in project %s"
                       (count test-nses) project))
      (let [exit (run-project-tests! project test-nses)]
        (when-not (zero? exit)
          (println "❌ Integration tests failed in project:" project)
          (System/exit exit))))))

(defn ^{:stratum 2} development
  "Run every development/test namespace from the repo root on the
   :dev:test:build classpath (:build because build-test loads build.clj,
   which needs tools.build). Tests tagged ^:external are skipped unless
   `external-tests-env` is \"true\". Exits non-zero on any failure or
   error, or when nothing is discovered."
  []
  (println "🧪 Running development tests...")
  (let [nses (discovered-development-tests)
        _ (when (empty? nses)
            (println "❌ No development tests discovered under" development-test-root)
            (System/exit 1))
        include-external? (= "true" (System/getenv external-tests-env))
        _ (when-not include-external?
            (println (str "Tests tagged ^:external call a real backend and are skipped; "
                          "set " external-tests-env "=true to run them.")))
        ns-args (apply str (map (fn [n] (str " '" n)) nses))
        expr (str "(require 'clojure.test" ns-args ") "
                  (when-not include-external? (external-test-skip-form nses))
                  "(let [r (clojure.test/run-tests" ns-args ")] "
                  "  (shutdown-agents) "
                  "  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))")
        env  (bb-test-runner/sanitize-git-worktree-env (into {} (System/getenv)))
        _ (println (format "Running %d development test namespaces" (count nses)))
        exit (run-stream! {:env env} (proc/clojure-command) "-M:dev:test:build" "-e" expr)]
    (when-not (zero? exit)
      (println "❌ Development tests failed with exit code:" exit)
      (System/exit exit))))

(defn ^{:stratum 2} precommit-smoke
  "Run the hand-curated pre-commit smoke set.

   Source of truth: resources/precommit-smoke-tests.edn. Aspirational
   target is <30 s; current realised wall time is ~110 s (dominated by
   JVM startup, see the PR doc's future-optimisation section). CI runs
   `bb test:all` for full coverage."
  []
  (println "🧪 Running pre-commit smoke tests...")
  (let [nses (read-precommit-smoke-nses!)
        clojure-cmd (proc/clojure-command)
        ;; The same space-prefixed quoted-namespace string drives both
        ;; (require …) and (clojure.test/run-tests …) — one expression,
        ;; one source of truth.
        ns-args (apply str (map (fn [n] (str " '" n)) nses))
        expr (str "(require 'clojure.test" ns-args ") "
                  "(let [r (clojure.test/run-tests" ns-args ")] "
                  "  (System/exit (if (zero? (+ (:fail r 0) (:error r 0))) 0 1)))")
        exit (run-stream! clojure-cmd "-M:test:dev" "-e" expr)]
    (when-not (zero? exit)
      (println "❌ Pre-commit smoke tests failed with exit code:" exit)
      (System/exit exit))
    (println (format "✓ Pre-commit smoke (%d namespaces) passed" (count nses)))))
