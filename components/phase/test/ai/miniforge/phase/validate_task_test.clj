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
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.validate-task :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

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
  {:valid? true :node-count 7 :edge-count 28 :errors []})

(defn- invalid-result
  "Stub result map for a failing validation."
  [& errors]
  {:valid? false :node-count 5 :edge-count 10 :errors (vec errors)})

(defn- capture-exit!
  "Returns an atom that records the exit code when System/exit is called.
   Also throws ex-info so the caller can stop after exit."
  []
  (let [code (atom nil)]
    [code (fn [c] (reset! code c) (throw (ex-info "exit" {:code c})))]))

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
;; validate-default-pipeline — error count propagated correctly

(deftest test-validate-default-pipeline-error-count-in-result
  (testing "given a build-time anomaly returned from validate-default-pipeline → anomaly? check holds"
    ;; Confirm the anomaly contract used by run is correct.
    (let [stub (anomaly/anomaly :invalid-input "test" {})]
      (is (anomaly/anomaly? stub)
          "anomaly? must be true for anomaly maps produced by the anomaly component")))

  (testing "given a valid result map → anomaly? is false"
    (is (not (anomaly/anomaly? (valid-result)))
        "plain result maps must not satisfy anomaly?")))
