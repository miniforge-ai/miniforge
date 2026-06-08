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

(ns ai.miniforge.phase.validate-task-test
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph-validator :as graph-validator]
   [ai.miniforge.phase.validate-task :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Named test-data constants (rule 006: values appear in both factory and assertions)

(def ^:private stub-node-count
  "Synthetic node count used in the valid-result stub.
   Chosen to be a visually distinct positive integer; its exact value is asserted
   in test-run-success-output-contains-node-and-edge-counts."
  7)

(def ^:private stub-edge-count
  "Synthetic edge count used in the valid-result stub.
   Chosen to be a visually distinct positive integer different from stub-node-count."
  28)

;------------------------------------------------------------------------------ Layer 0
;; Factories and helpers

(defn- violation-anomaly
  "Build a synthetic graph-validator anomaly for a given node and message."
  [node msg]
  {:anomaly/type    :invalid-input
   :anomaly/message msg
   :anomaly/data    {:node node}
   :anomaly/at      (java.time.Instant/now)})

(defn- valid-result
  "Stub result map for a passing validation."
  []
  {:valid? true :node-count stub-node-count :edge-count stub-edge-count :errors []})

(defn- invalid-result
  "Stub result map for a failing validation. Node/edge counts are arbitrary stubs."
  [& errors]
  {:valid? false :node-count 5 :edge-count 10 :errors (vec errors)})

(defn- capture-exit!
  "Returns an atom that records the exit code when System/exit is called.
   Also throws ex-info so the caller can stop after exit."
  []
  (let [code (atom nil)]
    [code (fn [c] (reset! code c) (throw (ex-info "exit" {:code c})))]))

(defn- capture-stderr
  "Execute thunk and return everything written to *err* as a string.
   Rebinds *err* to a PrintWriter backed by a StringWriter before calling thunk.
   Works with emit-failures / emit-build-error, both of which do
   (binding [*out* *err*] (println ...))."
  [thunk]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw true)]
    (binding [*err* pw]
      (thunk))
    (.toString sw)))

(defn- bb-on-path?
  "Returns true when the `bb` executable is found on PATH.
   Catches IOException so a missing bb is a skip, not an error."
  []
  (try
    (zero? (:exit (shell/sh "bb" "--version")))
    (catch java.io.IOException _
      false)))

;------------------------------------------------------------------------------ Layer 1
;; validate-default-pipeline — real invocation against defaults.edn

(deftest test-validate-default-pipeline-canonical-pipeline-passes
  (testing "given defaults.edn on the classpath → pipeline passes all structural checks"
    (let [result (sut/validate-default-pipeline)]
      (is (not (anomaly/anomaly? result))
          "should not be an anomaly — defaults.edn is well-formed")
      (is (true? (:valid? result))
          "canonical pipeline must pass properties 1–6")
      (is (pos? (:node-count result))
          "node count must be positive")
      (is (pos? (:edge-count result))
          "edge count must be positive")
      (is (empty? (:errors result))
          "canonical pipeline must produce zero violations"))))

(deftest test-property-7-skipped-in-bb-context
  (testing "validate-graph is called without :check-interceptors? → property 7 is not run"
    ;; validate-default-pipeline calls (graph-validator/validate-graph g) — 1-arity —
    ;; which maps to {:check-interceptors? false} inside validate-graph.
    ;; The spy captures the opts map seen by validate-graph and asserts the key is absent.
    (let [captured-opts (atom ::not-called)]
      (with-redefs [graph-validator/validate-graph
                    (fn
                      ([_g]
                       (reset! captured-opts {})
                       {:valid? true :errors [] :warnings []})
                      ([_g opts]
                       (reset! captured-opts opts)
                       {:valid? true :errors [] :warnings []}))]
        (sut/validate-default-pipeline))
      (is (not= ::not-called @captured-opts)
          "validate-graph must have been invoked")
      (is (not (true? (get @captured-opts :check-interceptors?)))
          "property 7 must not be requested — :check-interceptors? must be absent or false in bb context"))))

;------------------------------------------------------------------------------ Layer 1
;; run — exit-code contract, validated via System/exit mock

(deftest test-run-exits-zero-when-pipeline-valid
  (testing "given a valid pipeline result → run exits 0"
    (let [[code mock-exit] (capture-exit!)]
      (with-redefs [sut/validate-default-pipeline (constantly (valid-result))
                    sut/exit-process!             mock-exit]
        (try (sut/run) (catch clojure.lang.ExceptionInfo _)))
      (is (= 0 @code)
          "run must exit 0 when the pipeline is valid"))))

(deftest test-run-exits-one-when-pipeline-invalid
  (testing "given an invalid pipeline result → run exits 1"
    (let [[code mock-exit] (capture-exit!)]
      (with-redefs [sut/validate-default-pipeline
                    (constantly (invalid-result
                                 (violation-anomaly
                                  :verify
                                  "graph-validator: no terminal node reachable — stall risk")))
                    sut/exit-process! mock-exit]
        (try (sut/run) (catch clojure.lang.ExceptionInfo _)))
      (is (= 1 @code)
          "run must exit 1 when the pipeline has violations"))))

(deftest test-run-exits-one-when-graph-build-fails
  (testing "given a build-time anomaly (bad pipeline) → run exits 1"
    (let [[code mock-exit] (capture-exit!)]
      (with-redefs [sut/validate-default-pipeline
                    (constantly (anomaly/anomaly :invalid-input "empty pipeline" {}))
                    sut/exit-process! mock-exit]
        (try (sut/run) (catch clojure.lang.ExceptionInfo _)))
      (is (= 1 @code)
          "run must exit 1 when graph build fails"))))

;------------------------------------------------------------------------------ Layer 1
;; run — output format: success stdout and failure stderr

(deftest test-run-success-output-contains-node-and-edge-counts
  (testing "given a valid result → stdout contains node count and edge count"
    ;; success message template: \"Pipeline valid — {node-count} nodes, {edge-count} edges.\"
    (let [output (with-out-str
                   (with-redefs [sut/validate-default-pipeline (constantly (valid-result))
                                 sut/exit-process! (fn [_] nil)]
                     (sut/run)))]
      (is (str/includes? output (str stub-node-count))
          "success output must include the node count")
      (is (str/includes? output (str stub-edge-count))
          "success output must include the edge count"))))

(deftest test-run-failure-output-contains-violation-details
  (testing "given an invalid result → stderr contains phase name, message, and violation count"
    ;; violation-format template: \"  [phase: {phase}] {message} | data: {data}\"
    ;; summary-invalid template:  \"Pipeline validation failed: {error-count} violation(s) found.\"
    (let [err-output
          (capture-stderr
           (fn []
             (with-redefs [sut/validate-default-pipeline
                           (constantly (invalid-result
                                        (violation-anomaly
                                         :verify
                                         "graph-validator: no terminal node reachable")))
                           sut/exit-process! (fn [_] nil)]
               (sut/run))))]
      (is (str/includes? err-output "verify")
          "failure output must include the phase name from the violation")
      (is (str/includes? err-output "no terminal node reachable")
          "failure output must include the violation message")
      (is (str/includes? err-output "violation")
          "failure output must include the summary line containing 'violation'"))))

;------------------------------------------------------------------------------ Layer 1
;; validate-default-pipeline — anomaly contract

(deftest test-validate-default-pipeline-error-count-in-result
  (testing "given a build-time anomaly returned from validate-default-pipeline → anomaly? check holds"
    ;; Confirm the anomaly contract used by run is correct.
    (let [stub (anomaly/anomaly :invalid-input "test" {})]
      (is (anomaly/anomaly? stub)
          "anomaly? must be true for anomaly maps produced by the anomaly component")))

  (testing "given a valid result map → anomaly? is false"
    (is (not (anomaly/anomaly? (valid-result)))
        "plain result maps must not satisfy anomaly?")))

;------------------------------------------------------------------------------ Layer 1
;; bb loadability — smoke test that validate-task loads under babashka

(deftest test-bb-loadability-smoke
  (testing "ai.miniforge.phase.validate-task loads under babashka without errors"
    ;; Manual verification command (run from repo root):
    ;;   bb -e "(require 'ai.miniforge.phase.validate-task)"
    ;; Expected: exits 0, no output.
    ;;
    ;; This test shells out when bb is on PATH; otherwise passes with a skip notice.
    (if-not (bb-on-path?)
      (is true "skipped — bb not on PATH; manual check: bb -e \"(require 'ai.miniforge.phase.validate-task)\"")
      (let [{:keys [exit err]} (shell/sh "bb" "-e" "(require 'ai.miniforge.phase.validate-task)")]
        (is (zero? exit)
            (str "bb must load validate-task namespace without errors\n  stderr: " err))))))
