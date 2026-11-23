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
(ns ai.miniforge.phase-software-factory.release-test-support
  "Fixtures and context builders the release phase tests share: temp git
   worktrees, mock implement results, release contexts, and the logger and
   readiness helpers. Split out of release-test (rule 210); the
   definitions moved unchanged."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [babashka.fs :as fs]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase-software-factory.codex-pin :as codex-pin]))

;------------------------------------------------------------------------------ Layer 0

;; Test Fixtures
(defn ^{:stratum 0} with-unconfigured-codex
  "Run `f` with no codex configured.

   `build-executor-context` consults the codex for the release situation
   and appends the rendered landings to `:task/behavior-addendum`. The
   codex location comes from the environment (`MINIFORGE_CODEX_PATH`),
   so on a machine with one exported — the trap bench treated arm,
   2026-09-03 — the addendum assertions below saw pack text plus codex
   text where the test had stubbed only the pack. Nothing in this
   namespace is about the consultation itself; the pinning contract lives
   in codex-pin-test."
  [f]
  (with-redefs [codex-pin/configured-codex-dir (constantly nil)]
    (f)))

(def ^{:stratum 0} phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(defn ^{:stratum 0} create-temp-worktree
  "Create a temporary directory initialized as a real git repository.
   Required for git-dirty-files to work in the new environment model."
  []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "release-test-" (random-uuid)))]
    (.mkdirs temp-dir)
    ;; Initialize a real git repository so git status --porcelain works
    (shell/sh "git" "init" :dir (.getPath temp-dir))
    (.getPath temp-dir)))

(defn ^{:stratum 0} cleanup-temp-worktree
  [dir-path]
  (when dir-path
    (try
      (fs/delete-tree dir-path)
      (catch Exception _e nil))))

;; Mock Data
(def ^{:stratum 0} mock-code-artifact
  {:code/id (random-uuid)
   :code/files [{:path "src/feature.clj"
                 :content "(ns feature)\n(defn new-feature [] :implemented)"
                 :action :create}
                {:path "test/feature_test.clj"
                 :content "(ns feature-test)\n(deftest t (is true))"
                 :action :create}]
   :code/language "clojure"})

(def ^{:stratum 0} mock-implement-result
  "New-model implement phase result: carries environment reference and summary,
   NOT serialized :code/files. Code changes live in the worktree."
  {:status         :success
   :environment-id "test-environment-id"
   :summary        "Implemented feature: Add feature (2 files)"
   :metrics        {:tokens 1500 :duration-ms 3200}})

;; Diagnostic-emission regression tests
;;
;; These tests pin the observability behavior added 2026-05-04. The
;; production fix for the dogfood release-phase silent fail is two-part:
;;   1. Ensure the release-executor receives a non-nil logger even when
;;      the workflow ctx is missing :execution/logger.
;;   2. Emit log/error entries around the failure paths in enter-release
;;      so the next dogfood run yields the actual cause instead of a
;;      generic :anomalies.phase/agent-failed.
;; A future refactor that drops either guarantee should fail here, not
;; only surface during the next dogfood post-mortem.
(defn ^{:stratum 0} capturing-logger
  "Build a logger whose entries are appended to `entries-atom`. Used by
   the diagnostic-emission regression tests to assert log/error fires
   on the failure paths of enter-release."
  [entries-atom]
  (log/create-logger
    {:min-level :debug
     :output    (fn [entry] (swap! entries-atom conj entry))}))

(defn ^{:stratum 0} entry-events
  "Pull the :log/event keyword off every captured log entry — what the
   tests assert against."
  [entries-atom]
  (into #{} (keep :log/event) @entries-atom))

;; Boundary-commit rehydration regression
;;
;; Stage-3 dogfood (2026-05-07): plan/implement/verify/review all green,
;; files committed on the task branch by the implement-phase boundary,
;; but release threw `:release/zero-files` because `git-dirty-files`
;; saw a clean worktree. The fix mirrors review.clj/rehydrate-from-paths
;; — read content for the `:code/file-paths` recorded on the implement
;; artifact instead of relying on the worktree's dirty status.
(defn ^{:stratum 0} sh-must-succeed!
  "Run `git` with `args`; throw if exit != 0. Test-helper guard so
   we don't silently fall back through git-dirty-files when the
   boundary-commit simulation actually didn't commit."
  [worktree args]
  (let [result (apply shell/sh (concat args [:dir worktree]))]
    (when-not (zero? (:exit result))
      (throw (ex-info "git command failed inside test fixture"
                      {:args args
                       :exit (:exit result)
                       :err  (:err result)
                       :out  (:out result)})))
    result))

(defn ^{:stratum 0} readiness-ctx
  "A ctx whose pipeline configures `phases` and whose recorded results are
   `results` (phase -> phase map)."
  [phases results]
  {:execution/workflow {:workflow/pipeline (mapv #(hash-map :phase %) phases)}
   :execution/phase-results results})

(def ^{:stratum 0} verify-passed {:status :completed :result {:status :success}})

(defn ^{:stratum 0} review-decided [decision]
  {:status :completed :result {:status :success :output {:review/decision decision}}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-test-worktree
  [f]
  (let [worktree (create-temp-worktree)]
    (try
      (f worktree)
      (finally
        (cleanup-temp-worktree worktree)))))

(defn ^{:stratum 1} write-mock-files-to-worktree!
  "Write mock code artifact files to the test worktree.
   Simulates what the implement phase does in production: writing code
   directly to the execution environment's working directory."
  [worktree]
  (doseq [{:keys [path content]} (:code/files mock-code-artifact)]
    (let [file (io/file worktree path)]
      (io/make-parents file)
      (spit file content))))

(defn ^{:stratum 1} create-empty-context
  "Create a test context with NO files written to the worktree.
   Used to test zero-files detection (no git dirty files in environment)."
  [worktree]
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path worktree})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-base-context
  "Create a test context with mock files already written to the test worktree.
   In the new environment model, code changes are in the worktree (not phase
   results). Most tests use this context."
  [worktree]
  ;; Write mock files to the worktree, simulating what the implement phase does
  (write-mock-files-to-worktree! worktree)
  {:execution/id (random-uuid)
   :execution/input {:description "Test release"
                     :title "Add feature"
                     :intent "testing"}
   :execution/metrics {:tokens 0 :duration-ms 0}
   :execution/phase-results {:implement {:result mock-implement-result}}
   :worktree-path worktree})

(defn ^{:stratum 2} write-and-commit-mock-files!
  "Write the mock files into the worktree, commit them on a real
   git history, and assert the worktree is clean afterwards.

   Simulates the post-implement-boundary state where the agent's
   files have landed on the task branch and `git status --porcelain`
   reports nothing. Asserting cleanliness here means the regression
   test cannot accidentally pass via the legacy `git-dirty-files`
   path — it forces the rehydrate-from-paths code under test.

   Defeats global GPG / signing configuration (1Password, GPG agents)
   with `error: <agent> returned an error`. With commit.gpgsign=false
   locally on this repo AND --no-gpg-sign --no-verify on the commit
   itself, the fixture runs hermetically regardless of the dev
   machine's signing setup. (Same fix also applied via PR #858.)"
  [worktree]
  (write-mock-files-to-worktree! worktree)
  (sh-must-succeed! worktree ["git" "config" "user.email" "test@example.com"])
  (sh-must-succeed! worktree ["git" "config" "user.name" "Test"])
  (sh-must-succeed! worktree ["git" "config" "commit.gpgsign" "false"])
  (sh-must-succeed! worktree ["git" "config" "tag.gpgsign" "false"])
  (sh-must-succeed! worktree ["git" "add" "."])
  (sh-must-succeed! worktree ["git" "commit" "--no-gpg-sign" "--no-verify"
                              "-m" "implement-phase-boundary commit"])
  (let [{:keys [out]} (sh-must-succeed! worktree ["git" "status" "--porcelain"])]
    (when-not (str/blank? out)
      (throw (ex-info "fixture left worktree dirty after commit"
                      {:porcelain out})))))
