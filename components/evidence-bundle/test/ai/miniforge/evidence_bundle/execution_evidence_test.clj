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

(ns ai.miniforge.evidence-bundle.execution-evidence-test
  "Tests for N11 section 9.1 execution evidence collection.

   Covers:
   - collector/collect-execution-evidence (public, Layer 4.5)
   - evidence_bundle/extract-execution-evidence (private, Layer 1)
   - runner/extract-output evidence field enrichment"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.evidence-bundle.collector :as collector]
   [ai.miniforge.dag-executor.executor :as dag-exec]
   [ai.miniforge.workflow.runner :as runner]))

;; Private fn accessor for evidence_bundle.clj
(def ^:private extract-evidence-impl
  (var-get (ns-resolve 'ai.miniforge.evidence-bundle.protocols.impl.evidence-bundle
                       'extract-execution-evidence)))

;; ============================================================================
;; collector/collect-execution-evidence — pure data extraction
;; ============================================================================

(deftest collect-evidence-all-fields-present-test
  (testing "extracts all five evidence fields when present"
    (let [started  (java.time.Instant/parse "2026-04-09T10:00:00Z")
          finished (java.time.Instant/parse "2026-04-09T10:05:00Z")
          state {:execution/output
                 {:evidence/execution-mode  :governed
                  :evidence/runtime-class   :docker
                  :evidence/task-started-at started
                  :evidence/task-finished-at finished
                  :evidence/image-digest    "sha256:abc123"}}
          result (collector/collect-execution-evidence state)]
      (is (= :governed (:evidence/execution-mode result)))
      (is (= :docker (:evidence/runtime-class result)))
      (is (= started (:evidence/task-started-at result)))
      (is (= finished (:evidence/task-finished-at result)))
      (is (= "sha256:abc123" (:evidence/image-digest result))))))

(deftest collect-evidence-empty-output-test
  (testing "returns empty map when :execution/output is absent"
    (let [result (collector/collect-execution-evidence {})]
      (is (= {} result)))))

(deftest collect-evidence-nil-output-test
  (testing "returns empty map when :execution/output is nil"
    (let [result (collector/collect-execution-evidence {:execution/output nil})]
      (is (= {} result)))))

(deftest collect-evidence-partial-fields-test
  (testing "extracts only present fields, skips absent ones"
    (let [state {:execution/output
                 {:evidence/execution-mode :local
                  :evidence/runtime-class  :worktree
                  ;; no timestamps, no image-digest
                  :artifacts []}}
          result (collector/collect-execution-evidence state)]
      (is (= :local (:evidence/execution-mode result)))
      (is (= :worktree (:evidence/runtime-class result)))
      (is (not (contains? result :evidence/task-started-at)))
      (is (not (contains? result :evidence/task-finished-at)))
      (is (not (contains? result :evidence/image-digest))))))

(deftest collect-evidence-image-digest-only-test
  (testing "extracts image-digest alone when other fields absent"
    (let [state {:execution/output {:evidence/image-digest "sha256:deadbeef"}}
          result (collector/collect-execution-evidence state)]
      (is (= "sha256:deadbeef" (:evidence/image-digest result)))
      (is (not (contains? result :evidence/execution-mode))))))

;; ============================================================================
;; evidence_bundle/extract-execution-evidence (private) — same contract
;; ============================================================================

(deftest extract-impl-all-fields-test
  (testing "private extract-execution-evidence mirrors collector behavior"
    (let [started  (java.time.Instant/parse "2026-04-09T12:00:00Z")
          finished (java.time.Instant/parse "2026-04-09T12:01:00Z")
          state {:execution/output
                 {:evidence/execution-mode   :governed
                  :evidence/runtime-class    :docker
                  :evidence/task-started-at  started
                  :evidence/task-finished-at finished
                  :evidence/image-digest     "sha256:fff000"}}
          result (extract-evidence-impl state)]
      (is (= :governed (:evidence/execution-mode result)))
      (is (= :docker (:evidence/runtime-class result)))
      (is (= started (:evidence/task-started-at result)))
      (is (= finished (:evidence/task-finished-at result)))
      (is (= "sha256:fff000" (:evidence/image-digest result))))))

(deftest extract-impl-empty-state-test
  (testing "private fn returns empty map for empty state"
    (is (= {} (extract-evidence-impl {})))))

;; ============================================================================
;; runner/extract-output — evidence field enrichment
;; ============================================================================

(deftest extract-output-includes-evidence-fields-test
  (testing "extract-output populates evidence fields in :execution/output"
    (let [started (java.time.Instant/parse "2026-04-09T08:00:00Z")
          ctx {:execution/artifacts []
               :execution/phase-results {:done {:phase/status :succeeded}}
               :execution/current-phase :done
               :execution/status :completed
               :execution/mode :local
               :execution/started-at started}
          result (runner/extract-output ctx)
          output (:execution/output result)]
      (is (= :local (:evidence/execution-mode output)))
      (is (= started (:evidence/task-started-at output)))
      (is (some? (:evidence/task-finished-at output))
          "finished-at should be populated by extract-output")
      (is (inst? (:evidence/task-finished-at output))))))

(deftest extract-output-governed-mode-with-executor-test
  (testing "extract-output captures runtime-class from executor"
    (with-redefs [dag-exec/executor-type (constantly :docker)]
      (let [ctx {:execution/artifacts []
                 :execution/phase-results {}
                 :execution/current-phase nil
                 :execution/status :completed
                 :execution/mode :governed
                 :execution/executor :mock-executor
                 :execution/started-at (java.time.Instant/now)}
            output (:execution/output (runner/extract-output ctx))]
        (is (= :governed (:evidence/execution-mode output)))
        (is (= :docker (:evidence/runtime-class output)))))))

(deftest extract-output-no-executor-nil-runtime-class-test
  (testing "runtime-class is nil when no executor in context"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {}
               :execution/current-phase nil
               :execution/status :completed
               :execution/mode :local}
          output (:execution/output (runner/extract-output ctx))]
      (is (nil? (:evidence/runtime-class output))))))

(deftest extract-output-image-digest-from-metadata-test
  (testing "extract-output includes image-digest from environment metadata"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {}
               :execution/current-phase nil
               :execution/status :completed
               :execution/mode :governed
               :execution/environment-metadata {:image-digest "sha256:abc123"}}
          output (:execution/output (runner/extract-output ctx))]
      (is (= "sha256:abc123" (:evidence/image-digest output))))))

(deftest extract-output-no-image-digest-when-absent-test
  (testing "extract-output omits :evidence/image-digest when not in metadata"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {}
               :execution/current-phase nil
               :execution/status :completed
               :execution/mode :local}
          output (:execution/output (runner/extract-output ctx))]
      (is (not (contains? output :evidence/image-digest))))))

(deftest extract-output-executor-type-exception-caught-test
  (testing "extract-output catches exception from executor-type gracefully"
    (with-redefs [dag-exec/executor-type
                  (fn [_] (throw (ex-info "executor disposed" {})))]
      (let [ctx {:execution/artifacts []
                 :execution/phase-results {}
                 :execution/current-phase nil
                 :execution/status :completed
                 :execution/executor :broken
                 :execution/mode :governed}
            output (:execution/output (runner/extract-output ctx))]
        (is (nil? (:evidence/runtime-class output)))))))

(deftest extract-output-defaults-execution-mode-to-local-test
  (testing "execution-mode defaults to :local when :execution/mode absent"
    (let [ctx {:execution/artifacts []
               :execution/phase-results {}
               :execution/current-phase nil
               :execution/status :completed}
          output (:execution/output (runner/extract-output ctx))]
      (is (= :local (:evidence/execution-mode output))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.execution-evidence-test)
  :leave-this-here)
