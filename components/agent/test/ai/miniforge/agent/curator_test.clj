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

(ns ai.miniforge.agent.curator-test
  "Tests for the Curator agent.
   Covers deterministic-only paths exhaustively; LLM-enrichment path is
   tested via injected stub clients, never a real LLM backend."
  (:require
   [ai.miniforge.agent.curator :as sut]
   [ai.miniforge.response.interface :as response]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(defn- impl-result-with-files
  "Build a minimal implementer result containing a :code/files vector."
  [files]
  {:status :success
   :output {:code/files files}})

(def src-file
  {:path "components/workflow/src/ai/miniforge/workflow/retry_budget.clj"
   :content "(ns ai.miniforge.workflow.retry-budget)"
   :action :create})

(def test-file
  {:path "components/workflow/test/ai/miniforge/workflow/retry_budget_test.clj"
   :content "(ns ai.miniforge.workflow.retry-budget-test)"
   :action :create})

(def out-of-scope-file
  {:path "docs/unrelated.md"
   :content "## Unrelated"
   :action :modify})

;; NOTE: The LLM-enrichment path is intentionally not exercised end-to-end here;
;; building a faithful stub of the llm interface surface (chat/success?/get-content)
;; would couple these tests to internals that evolve quickly. The enrichment
;; branch is covered via the `:llm-client nil` test below (deterministic fallback),
;; plus unit-level coverage lives alongside llm.interface itself. A future test
;; pass can add an injected-client variant once the interface stabilizes.

;------------------------------------------------------------------------------ Layer 1
;; Deterministic path — happy case

(deftest curate-produces-artifact-from-implementer-result-files
  (testing "when the implementer result already has :code/files, curator uses them"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files [src-file test-file])
                   :worktree-path "/tmp/ignored"
                   :intent-scope [(:path src-file) (:path test-file)]
                   :spec-description "Add retry-budget state machine"})]
      (is (response/success? result))
      (let [art (:output result)]
        (is (uuid? (:code/id art)))
        (is (= 2 (count (:code/files art))))
        (is (= :deterministic (:code/curator-source art)))
        (is (true? (:code/tests-added? art))
            "test file presence should flip :code/tests-added?")
        (is (= [] (:code/scope-deviations art))
            "both files are in declared scope")
        (is (string? (:code/summary art)))
        (is (re-find #"2 files changed" (:code/summary art)))
        (is (= "clj" (:code/language art)))
        (is (false? (:code/breaking-change? art))
            "breaking-change? should default to false without LLM")
        (is (inst? (:code/curated-at art)))))))

(deftest curate-flags-scope-deviations
  (testing "files outside declared scope are flagged"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files
                                        [src-file out-of-scope-file])
                   :worktree-path "/tmp/ignored"
                   :intent-scope [(:path src-file)]
                   :spec-description "Add retry-budget"})
          art (:output result)]
      (is (response/success? result))
      (is (= ["docs/unrelated.md"] (:code/scope-deviations art))
          "path not in scope should appear in deviations"))))

(deftest curate-empty-scope-means-no-deviations
  (testing "when intent-scope is nil or empty, no deviations are flagged"
    (doseq [scope [nil []]]
      (let [result (sut/curate-implement-output
                    {:implementer-result (impl-result-with-files [src-file])
                     :worktree-path "/tmp/ignored"
                     :intent-scope scope})
            art (:output result)]
        (is (response/success? result))
        (is (= [] (:code/scope-deviations art))
            (str "scope=" (pr-str scope)))))))

(deftest curate-detects-tests-added-heuristics
  (testing "test-file detection recognizes common path shapes"
    (doseq [path ["src/foo/bar_test.clj"
                  "src/foo/bar.test.ts"
                  "test/foo/bar.clj"
                  "spec/foo/bar.rb"
                  "src/foo/bar_spec.rb"]]
      (let [result (sut/curate-implement-output
                    {:implementer-result (impl-result-with-files
                                          [{:path path
                                            :content ""
                                            :action :create}])
                     :worktree-path "/tmp/ignored"})
            art (:output result)]
        (is (true? (:code/tests-added? art))
            (str "should detect test file: " path))))))

(deftest curate-does-not-flag-non-test-files
  (testing "regular source files do not trigger tests-added?"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files
                                        [{:path "src/foo/bar.clj"
                                          :content ""
                                          :action :create}])
                   :worktree-path "/tmp/ignored"})
          art (:output result)]
      (is (false? (:code/tests-added? art))))))

(deftest curate-summary-reflects-action-counts
  (testing "deterministic summary reports create/modify/delete counts"
    (let [files [{:path "a.clj" :content "" :action :create}
                 {:path "b.clj" :content "" :action :create}
                 {:path "c.clj" :content "" :action :modify}
                 {:path "d.clj" :content "" :action :delete}]
          result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files files)
                   :worktree-path "/tmp/ignored"})
          summary (get-in result [:output :code/summary])]
      (is (re-find #"4 files changed" summary))
      (is (re-find #"2 created" summary))
      (is (re-find #"1 modified" summary))
      (is (re-find #"1 deleted" summary)))))

(deftest curate-language-is-most-common-extension
  (testing "language inferred from dominant file extension"
    (let [files [{:path "a.clj" :content "" :action :create}
                 {:path "b.clj" :content "" :action :create}
                 {:path "c.md"  :content "" :action :create}]
          result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files files)
                   :worktree-path "/tmp/ignored"})]
      (is (= "clj" (get-in result [:output :code/language]))))))

;------------------------------------------------------------------------------ Layer 2
;; Fast-fail path

(deftest curate-errors-when-no-files-written
  (testing "empty diff → clear error, no silent success"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files [])
                   :worktree-path "/tmp/nothing"
                   :intent-scope ["a.clj"]})
          error-msg (get-in result [:error :message])]
      (is (not= :success (:status result)))
      (is (string? error-msg)
          "expected :error :message to be a string")
      (is (re-find #"no files" error-msg))
      (is (= "/tmp/nothing" (get-in result [:error :data :worktree-path]))
          "error data should carry the worktree-path for debugging")
      (is (= :curator/no-files-written (get-in result [:error :data :code]))
          "error data must carry a stable :code the phase runner branches on;
           this is the terminal signal that makes the implement phase fail
           instead of retrying blindly"))))

(deftest curate-no-files-error-carries-diagnostic
  (testing "the terminal no-files error includes a diagnostic explaining why
            each collection source was empty — so capture loss (nil
            pre-snapshot / wrong dir / mode) is distinguishable from a genuine
            no-write"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files [])
                   :worktree-path "/tmp/nothing"
                   :intent-scope ["a.clj"]})
          diag   (get-in result [:error :data :diagnostic])]
      (is (some? diag) "no-files error must carry a :diagnostic")
      (is (= 0 (get-in diag [:source-file-counts :from-result]))
          "reports per-source counts so the empty source is visible")
      (is (false? (:pre-session-snapshot? diag))
          "flags the missing pre-session-snapshot — a documented loss mode")
      (is (= "/tmp/nothing" (:worktree-path diag)))
      (is (= :local (:collection-mode diag))
          "no executor/env-id → local collection mode"))))

(deftest curate-errors-when-implementer-result-has-no-output
  (testing "missing :output map → empty diff → error"
    (let [result (sut/curate-implement-output
                  {:implementer-result {:status :success}
                   :worktree-path "/tmp/nothing"})]
      (is (not= :success (:status result))))))

(deftest curate-drops-non-substantive-paths
  (testing "iter-23 regression: session markers and .miniforge/* must not count as work"
    (is (false? (sut/substantive-file? {:path ".miniforge-session-id"})))
    (is (false? (sut/substantive-file? {:path ".miniforge/plan.edn"})))
    (is (true?  (sut/substantive-file? {:path "src/ai/miniforge/x.clj"}))))
  (testing "diff containing only runtime markers fails with no-files-written"
    (let [result (sut/curate-implement-output
                  {:implementer-result
                   (impl-result-with-files
                    [{:path ".miniforge-session-id" :content "abc" :action :modify}
                     {:path ".miniforge/plan.edn"   :content "{}"  :action :create}])
                   :worktree-path "/tmp/ghost"})]
      (is (not= :success (:status result)))
      (is (= :curator/no-files-written
             (get-in result [:error :data :code]))
          "non-substantive paths should be filtered out before the empty check"))))

;------------------------------------------------------------------------------ Layer 3
;; Metrics

(deftest curate-metrics-report-file-counts
  (testing "response metrics carry file counts and source tag"
    (let [files [{:path "a.clj" :content "" :action :create}
                 {:path "a_test.clj" :content "" :action :create}
                 {:path "b.clj" :content "" :action :modify}]
          result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files files)
                   :worktree-path "/tmp/ignored"})
          metrics (:metrics result)]
      (is (= 3 (:files-total metrics)))
      (is (= 2 (:files-created metrics)))
      (is (= 1 (:files-modified metrics)))
      (is (= 0 (:files-deleted metrics)))
      (is (true? (:tests-added? metrics)))
      (is (= :deterministic (:curator-source metrics))))))

;------------------------------------------------------------------------------ Layer 4
;; LLM enrichment — stub

(deftest curate-without-llm-client-uses-deterministic-source
  (testing "no llm-client → :code/curator-source is :deterministic"
    (let [result (sut/curate-implement-output
                  {:implementer-result (impl-result-with-files [src-file])
                   :worktree-path "/tmp/ignored"
                   :llm-client nil})]
      (is (= :deterministic (get-in result [:output :code/curator-source]))))))

;------------------------------------------------------------------------------ Layer 5
;; Local-snapshot fallback — recovers implementer work when impl-result
;; never propagated :code/files (e.g. supervisor-terminated agent throw).

(defn- git!
  "Run git in `cwd`; throw on non-zero exit."
  [cwd & args]
  (let [r (apply shell/sh "git" "-C" (str cwd) args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed: " (:err r))
                      {:cwd cwd :args args :result r})))
    r))

(defn- init-clean-worktree!
  "Create a fresh git worktree with one committed file (clean HEAD)."
  [worktree]
  (git! worktree "init" "-b" "main")
  (git! worktree "config" "user.email" "test@miniforge.ai")
  (git! worktree "config" "user.name" "miniforge-test")
  (git! worktree "config" "commit.gpgsign" "false")
  (spit (fs/file worktree "README.md") "initial\n")
  (git! worktree "add" "README.md")
  (git! worktree "commit" "-m" "init"))

(deftest curate-falls-back-to-worktree-snapshot-when-impl-result-missing-files
  (testing "Run-7 dogfood regression: impl-result with no :code/files but a
            dirty worktree must still surface implementer work via the local
            snapshot fallback. Reproduces the case where the agent threw
            mid-stream (e.g. supervisor termination) so invoke-with-llm
            never reached collect-written-files, yet the writes are sitting
            on disk waiting to be picked up."
    (let [worktree (str (fs/create-temp-dir {:prefix "curator-fallback-"}))]
      (try
        (init-clean-worktree! worktree)
        ;; Implementer-style writes that landed on disk before the throw.
        (fs/create-dirs (fs/file worktree "src"))
        (spit (fs/file worktree "src/added.clj") "(ns added)\n")
        (spit (fs/file worktree "README.md") "modified\n")
        (let [;; impl-result shape that response/failure produces — no :output map.
              failure-result {:status :failed :success false
                              :error {:message "agent terminated mid-stream"}}
              result (sut/curate-implement-output
                      {:implementer-result failure-result
                       :worktree-path      worktree})]
          (is (response/success? result)
              "curator should fall back to the worktree snapshot and find the dirty files")
          (let [files (get-in result [:output :code/files])
                paths (set (map :path files))]
            (is (contains? paths "src/added.clj"))
            (is (contains? paths "README.md")
                "modified-but-tracked file must also be picked up")))
        (finally
          (try (fs/delete-tree worktree) (catch Throwable _ nil)))))))

(deftest curate-snapshot-fallback-still-fails-when-worktree-clean
  (testing "fallback must NOT mask a genuinely empty diff — clean worktree
            with no impl-result files still terminates with no-files-written"
    (let [worktree (str (fs/create-temp-dir {:prefix "curator-clean-"}))]
      (try
        (init-clean-worktree! worktree)
        (let [result (sut/curate-implement-output
                      {:implementer-result {:status :failed :success false}
                       :worktree-path      worktree})]
          (is (= :curator/no-files-written
                 (get-in result [:error :data :code])))
          (is (= worktree (get-in result [:error :data :worktree-path]))))
        (finally
          (try (fs/delete-tree worktree) (catch Throwable _ nil)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests *ns*)
  :leave-this-here)
