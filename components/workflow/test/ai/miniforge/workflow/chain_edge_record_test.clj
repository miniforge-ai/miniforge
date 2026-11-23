;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-record-test
  (:require [ai.miniforge.workflow.chain-edge-record :as record]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-record [target expression value]
  {:binding/target-input target :binding/expression expression :binding/value value
   :binding/resolved? true :binding/required? false})

(defn- ^{:stratum 0} edge []
  (record/started (random-uuid) (random-uuid) (random-uuid)
                  {:value [:prev/last-phase-result :value]} (java.util.Date. 0)))

(deftest ^{:stratum 0} only-predecessor-expressions-declare-workflow-dependencies
  (let [declarations {:value [:prev/last-phase-result :value] :whole []
                      :input :chain/input.task :literal "fixture" :key :flag}]
    (is (= (select-keys declarations [:value :whole])
           (record/dependencies {:step/input-bindings declarations}))))
  (is (= {} (record/dependencies {}))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} completion-records-binding-work-not-child-execution
  (let [started (edge)
        bindings [(binding-record "value" [:prev/last-phase-result :value] false)
                  (binding-record "literal" "fixture" "fixture")]
        completed (record/completed started bindings (java.util.Date. 1) 1)
        resolved (first (:edge/bindings completed))]
    (is (= :running (:edge/status started)))
    (is (= :completed (:edge/status completed)))
    (is (= (record/identity-fields started) (record/identity-fields completed)))
    (is (= 1 (:edge/bindings-count started) (count (:edge/bindings completed))))
    (is (= (:edge/from-workflow-id started) (:binding/source-workflow-id resolved)))
    (is (= "[:prev/last-phase-result :value]" (:binding/source-output-path resolved)))
    (is (false? (:binding/value resolved)))
    (is (not (contains? completed :execution/status)))))

(deftest ^{:stratum 1} failure-retains-edge-identity-and-existing-binding-records
  (let [started (edge)
        completed (record/completed started [(binding-record "value" [:prev/artifacts] [])] (java.util.Date. 1) 1)
        failed (record/failed completed "fixture publication failure" (java.util.Date. 2))]
    (is (= :failed (:edge/status failed)))
    (is (= (record/identity-fields started) (record/identity-fields failed)))
    (is (= (:edge/bindings completed) (:edge/bindings failed)))
    (is (= "fixture publication failure" (:edge/failure-reason failed)))))

(comment
  (edge))
