;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.chain-binding-redaction-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} binding-record []
  {:binding/target-input "password" :binding/expression "fixture-password" :binding/value "fixture-password"
   :binding/resolved? true :binding/required? false})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} imported-binding-reports-are-redacted-in-both-evidence-locations
  (let [record (binding-record)
        definition {:chain/id :fixture :chain/version "1.0.0"}
        result {:chain/status :completed
                :chain/definition-snapshot {:step/input-bindings {:password "fixture-password"}}
                :chain/step-results [{:step/id :first :step/status :completed :step/bindings [record]}]
                :chain/edges [{:edge/id (random-uuid) :edge/bindings [record]}]}
        projection (evidence/create-chain-evidence definition result)
        step-record (get-in projection [:chain-evidence/step-summaries 0 :step/bindings 0])
        edge-record (get-in projection [:chain/edges 0 :edge/bindings 0])]
    (is (= (redaction/marker) (:binding/value step-record) (:binding/value edge-record)))
    (is (= (redaction/marker) (:binding/expression step-record) (:binding/expression edge-record)))
    (is (not (str/includes? (pr-str projection) "fixture-password")))
    (is (= "fixture-password" (:binding/value record)))))

(comment
  (binding-record))
