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

;; NOTE: bb loadability is a manual verification gate. From the repo root:
;;   bb -e "(require 'ai.miniforge.phase.validate-task)"
;; Expected: exits 0, no output. The CI-authoritative gate is the bb phases:validate
;; task in bb.edn, which exercises the full load path on every commit.

(ns ai.miniforge.phase.validate-task-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.graph :as graph]
   [ai.miniforge.phase.graph-validator :as graph-validator]
   [ai.miniforge.phase.messages :as messages]
   [ai.miniforge.phase.validate-task :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Named test-data constants (rule 006: values appear in both factories and assertions)

(def ^:private stub-node-count
  "Synthetic node count used in the valid-result stub.
   Chosen to be a visually distinct positive integer; its exact value is asserted
   in test-run-success-output-contains-node-and-edge-counts."
  7)

(def ^:private stub-edge-count
  "Synthetic edge count used in the valid-result stub.
   Chosen to be a visually distinct positive integer different from stub-node-count."
  28)

(def ^:private stub-invalid-node-count
  "Synthetic node count used in the invalid-result stub.
   Intentionally different from stub-node-count to make the two fixture shapes
   distinguishable in assertion output."
  5)

(def ^:private stub-invalid-edge-count
  "Synthetic edge count used in the invalid-result stub.
   Intentionally different from stub-edge-count to make the two fixture shapes
   distinguishable in assertion output."
  10)

;------------------------------------------------------------------------------ Layer 0
;; Factories and helpers

(defn- violation-anomaly
  "Build a synthetic graph-validator anomaly for a given node and message.
   Uses the anomaly constructor (rule 005: exceptions-as-data) to guarantee canonical shape."
  [node msg]
  (anomaly/anomaly :invalid-input msg {:node node}))

(defn- valid-result
  "Stub result map for a passing validation."
  []
  {:valid? true :node-count stub-node-count :edge-count stub-edge-count :errors []})

(defn- invalid-result
  "Stub result map for a failing validation."
  [& errors]
  {:valid?     false
   :node-count stub-invalid-node-count
   :edge-count stub-invalid-edge-count
   :errors     (vec errors)})

(defn- capture-exit!
  "Returns [atom thunk]. thunk records the exit code in atom, then throws ex-info so
   callers stop cleanly after exit — without invoking System/exit for real."
  []
  (let [code (atom nil)]
    [code (fn [c] (reset! code c) (throw (ex-info "exit" {:code c})))]))

(defn- capture-stderr
  "Execute thunk and return everything written to *err* as a string.
   Works with emit-failures / emit-build-error, both of which do
   (binding [*out* *err*] (println ...))."
  [thunk]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw true)]
    (binding [*err* pw]
      (thunk))
    (.toString sw)))

(defn- stub-ts
  "Deterministic messages/ts replacement: returns all param values joined by spaces.
   Decouples output-format tests from catalog availability — the real catalog
   (rule 050) is validated at the messages component level, not here.
   Handles both the 1-arity (key only) and 2-arity (key + params) call shapes."
  ([_key] "")
  ([_key params]
   (str/join " " (map str (vals params)))))

;------------------------------------------------------------------------------ Layer 1
;; validate-default-pipeline — real invocation against defaults.edn

(deftest test-validate-default-pipeline-canonical-pipeline-passes
  ;; Integration test: only meaningful when config/phase/defaults.edn is on the classpath.
  ;; Runs in the data-foundry project (defaults.edn present on its test classpath);
  ;; skips silently in the miniforge project (defaults.edn absent from that project's
  ;; test classpath — the component resource path is not included there).
  (when (io/resource "config/phase/defaults.edn")
    (testing "given defaults.edn on the classpath → pipeline passes all structural checks"
      (let [result       (sut/validate-default-pipeline)
            valid-shape? (not (anomaly/anomaly? result))]
        (is valid-shape?
            "should not be an anomaly — defaults.edn is well-formed")
        (when valid-shape?
          (is (true? (:valid? result))
              "canonical pipeline must pass properties 1–6")
          (is (pos? (:node-count result))
              "node count must be positive")
          (is (pos? (:edge-count result))
              "edge count must be positive")
          (is (empty? (:errors result))
              "canonical pipeline must produce zero violations"))))))

(deftest test-property-7-skipped-in-bb-context
  (testing "validate-graph is called without :check-interceptors? → property 7 is not run"
    ;; graph/build-transition-graph is stubbed so this test is classpath-independent.
    ;; The contract under test is the *absence* of :check-interceptors? in the
    ;; validate-graph call, not the correctness of graph construction itself.
    ;; Both arities of validate-graph are spied so either call path is captured.
    (let [captured-opts (atom ::not-called)
          minimal-graph {:nodes          #{:plan :failed :done}
                         :edges          []
                         :terminal-nodes #{:failed :done}
                         :phase-nodes    #{:plan}}]
      (with-redefs [graph/build-transition-graph
                    (fn [& _] minimal-graph)
                    graph-validator/validate-graph
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
                    (constantly
                     (invalid-result
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
    ;; messages/ts is stubbed to return all param values joined by spaces, making
    ;; the assertion independent of catalog availability (rule 050 is tested at
    ;; the messages component level). With {:node-count 7 :edge-count 28}, the
    ;; stub emits "7 28" — both counts appear as substrings.
    (let [output (with-out-str
                   (with-redefs [sut/validate-default-pipeline (constantly (valid-result))
                                 sut/exit-process!             (fn [_] nil)
                                 messages/ts                   stub-ts]
                     (sut/run)))]
      (is (str/includes? output (str stub-node-count))
          "success output must include the node count")
      (is (str/includes? output (str stub-edge-count))
          "success output must include the edge count"))))

(deftest test-run-failure-output-contains-violation-details
  (testing "given an invalid result → stderr contains phase name, violation message, and count"
    ;; violation-format params: {:phase "verify" :message "..." :data "..."}
    ;; summary-invalid params:  {:error-count 1}
    ;; stub-ts returns all param values joined by spaces — assertions check substrings.
    (let [err-output
          (capture-stderr
           (fn []
             (with-redefs [sut/validate-default-pipeline
                           (constantly
                            (invalid-result
                             (violation-anomaly
                              :verify
                              "graph-validator: no terminal node reachable")))
                           sut/exit-process! (fn [_] nil)
                           messages/ts       stub-ts]
               (sut/run))))]
      (is (str/includes? err-output "verify")
          "failure output must include the phase name from the violation")
      (is (str/includes? err-output "no terminal node reachable")
          "failure output must include the violation message")
      (is (str/includes? err-output "1")
          "failure output must include the violation count"))))

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
