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

(ns ai.miniforge.phase.graph-validator-integration-test
  "Integration tests for graph-validator.
   Exercises the interface.clj re-exports and the validate-pipeline-graph
   convenience function end-to-end. Property-level and aggregate tests live
   in graph-validator-test; this file only covers the integration paths."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.phase.interface :as phase-interface]
   [ai.miniforge.phase.graph-validator :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Shared fixtures

(def ^:private sdlc-default-pipeline
  "Canonical miniforge SDLC pipeline — mirrors graph-test.clj's guarded-pipeline
   and the graph.clj REPL comment block. See graph-validator-test for the
   regression pin that validates this pipeline against all structural checks."
  [{:phase :plan}
   {:phase :implement :budget {:iterations 3} :on-fail :plan}
   {:phase :verify    :on-fail :implement}
   {:phase :review    :on-fail :implement}
   {:phase :done :terminal? true}])

(defn- anomaly-data
  "Extract :anomaly/data from an anomaly map."
  [a]
  (:anomaly/data a))

;------------------------------------------------------------------------------ Layer 1
;; Integration — interface.clj re-exports

(deftest interface-build-transition-graph-resolves
  (testing "interface/build-transition-graph returns a TransitionGraph (not an anomaly)"
    (let [pipeline [{:phase :plan}
                    {:phase :implement}
                    {:phase :done :terminal? true}]
          result   (phase-interface/build-transition-graph pipeline)]
      (is (not (anomaly/anomaly? result))
          (str "expected TransitionGraph; got: " result))
      (is (set?    (:nodes result)))
      (is (vector? (:edges result)))
      (is (set?    (:terminal-nodes result)))
      (is (set?    (:phase-nodes    result)))))
  (testing "interface/build-transition-graph forwards anomaly on malformed pipeline"
    (is (anomaly/anomaly? (phase-interface/build-transition-graph []))))
  (testing "graph produced by interface/build-transition-graph validates correctly"
    ;; :implement has :on-fail :plan (redirect cycle) AND :budget {:iterations 3}
    ;; so check-cycles treats the plan→implement→plan loop as bounded (not runaway).
    (let [pipeline [{:phase :plan}
                    {:phase :implement :on-fail :plan :budget {:iterations 3}}
                    {:phase :done :terminal? true}]
          tg       (phase-interface/build-transition-graph pipeline)
          result   (sut/validate-graph tg)]
      (is (true? (:valid? result))
          (str "expected :valid? true; errors: " (mapv :anomaly/message (:errors result)))))))

;------------------------------------------------------------------------------ Layer 1
;; Integration — validate-pipeline-graph end-to-end

(deftest validate-pipeline-graph-happy-path
  (testing "valid pipeline returns {:valid? true :errors [] :warnings []}"
    (let [result (sut/validate-pipeline-graph sdlc-default-pipeline)]
      (is (true?  (:valid? result))
          (str "expected :valid? true; errors: " (mapv :anomaly/message (:errors result))))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))))
  (testing "single-phase terminal pipeline also passes"
    (let [result (sut/validate-pipeline-graph [{:phase :plan}
                                               {:phase :done :terminal? true}])]
      (is (true? (:valid? result))
          (str "expected :valid? true; errors: " (mapv :anomaly/message (:errors result)))))))

(deftest validate-pipeline-graph-malformed-pipeline
  (testing "empty pipeline → anomaly map returned directly (not a graph-validation result)"
    (let [result (sut/validate-pipeline-graph [])]
      (is (anomaly/anomaly? result)
          "empty pipeline should produce anomaly, not {:valid? ...}")))
  (testing "nil pipeline → anomaly map returned directly"
    (is (anomaly/anomaly? (sut/validate-pipeline-graph nil))))
  (testing "duplicate phases → anomaly map returned directly"
    (is (anomaly/anomaly? (sut/validate-pipeline-graph [{:phase :plan} {:phase :plan}]))))
  (testing "malformed entry (no :phase key) → anomaly map returned directly"
    (is (anomaly/anomaly? (sut/validate-pipeline-graph [{:name :broken}])))))

(deftest validate-pipeline-graph-with-opts
  (testing "check-interceptors? forwarded through opts — unregistered phase flagged"
    (let [pipeline [{:phase :plan}
                    {:phase :done :terminal? true}]
          opts     {:check-interceptors?       true
                    :interceptor-registered-fn (constantly false)}
          result   (sut/validate-pipeline-graph pipeline opts)]
      (is (false? (:valid? result)))
      (is (pos?   (count (:errors result))))
      (is (some #(= :plan (:phase (anomaly-data %))) (:errors result))))))

(comment
  ;; Quick REPL smoke-tests for this integration test namespace
  (require '[clojure.test :as t])
  (t/run-tests 'ai.miniforge.phase.graph-validator-integration-test)
  )
