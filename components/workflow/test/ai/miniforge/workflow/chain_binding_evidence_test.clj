;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-evidence-test
  (:require [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.workflow.chain-binding-report :as report]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-record [target expression value]
  {:binding/target-input target :binding/expression expression :binding/value value
   :binding/resolved? true :binding/required? false})

(deftest ^{:stratum 0} execution-inputs-are-not-replaced-by-reporting-markers
  (let [declarations {:password :chain/input.password :alias [:prev/last-phase-result :password]}
        result (report/resolve-inputs declarations {:last-phase-result {:password "fixture-password"}}
                                      {:password "fixture-password"})]
    (is (= {:password "fixture-password" :alias "fixture-password"} (:inputs result)))
    (is (every? #(= (redaction/marker) (:binding/value %)) (:bindings result)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} reporting-retains-target-and-source-secret-context
  (doseq [[target expression] [["password" "fixture-password"]
                               ["alias" [:prev/last-phase-result :password]]
                               ["alias" :chain/input.password]
                               ["alias" [:prev/last-phase-result :credentials 0]]]]
    (let [result (workflow/redact-chain-binding (binding-record target expression "fixture-password"))]
      (is (= (redaction/marker) (:binding/value result)))
      (is (true? (:binding/resolved? result)))
      (when (string? expression)
        (is (= (redaction/marker) (:binding/expression result)))))))

(deftest ^{:stratum 1} generic-values-still-use-the-shared-redaction-policy
  (doseq [[value expected] [[false false] [42 42]
                            [{:password "fixture-password"} {:password (redaction/marker)}]
                            ["sk-abcdefghijklmnopqrst" (redaction/marker)]
                            [(atom "fixture-password") (redaction/marker)]]]
    (let [record (binding-record "value" [:prev/artifacts] value)
          result (workflow/redact-chain-binding record)]
      (is (= expected (:binding/value result)))
      (is (= result (workflow/redact-chain-binding result))))))

(comment
  (binding-record "value" :value false))
