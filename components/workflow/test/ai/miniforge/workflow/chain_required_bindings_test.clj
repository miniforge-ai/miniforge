;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-required-bindings-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.chain-binding-report :as report]
            [ai.miniforge.workflow.chain-binding-spec :as spec]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} descriptor [expression required?]
  {:binding/expression expression :binding/required? required?})

(defn- ^{:stratum 0} definition [declaration]
  (assoc-in (support/definition :required-bindings "1.0.0")
            [:chain/steps 0 :step/input-bindings] {:value declaration}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} descriptors-are-explicit-and-preserve-legacy-shorthand
  (doseq [value ["literal" :chain/input.task [:prev/artifacts]
                 (descriptor [:prev/artifacts] true) (descriptor [] false)]]
    (is (schema/valid? spec/Binding value)))
  (doseq [value [(descriptor :chain/input.task true) (descriptor "literal" true)
                 (descriptor [:prev/artifacts] nil) (descriptor [:prev/artifacts] "true")
                 {:binding/expression [:prev/artifacts]}
                 (assoc (descriptor [:prev/artifacts] true) :unknown true)
                 (descriptor (descriptor [:prev/artifacts] true) true)]]
    (is (not (schema/valid? spec/Binding value)))))

(deftest ^{:stratum 1} invalid-required-declarations-refuse-before-invocation-allocation
  (let [calls (atom [])]
    (with-redefs [composition/run-record #(swap! calls conj %)]
      (is (anomaly/anomaly? (workflow/run-chain (definition (descriptor :wrong true)) {} {}))))
    (is (empty? @calls))))

(deftest ^{:stratum 1} snapshot-detaches-the-descriptor-and-expression
  (let [declaration (into (sorted-map) (descriptor [:prev/artifacts] true))
        detached (composition/detach (definition declaration))
        copied (get-in detached [:chain/steps 0 :step/input-bindings :value])]
    (is (= declaration copied))
    (is (not (identical? declaration copied)))
    (is (not (instance? clojure.lang.Sorted copied)))
    (is (not (identical? (:binding/expression declaration) (:binding/expression copied))))))

(deftest ^{:stratum 1} false-and-empty-values-satisfy-required-bindings
  (doseq [value [false [] {} ""]]
    (let [result (report/resolve-inputs {:value (descriptor [:prev/last-phase-result :value] true)}
                                      {:last-phase-result {:value value}} {})
          record (first (:bindings result))]
      (is (= {:value value} (:inputs result)))
      (is (true? (:binding/required? record)))
      (is (true? (:binding/resolved? record)))
      (is (= value (:binding/value record))))))

(deftest ^{:stratum 1} required-missing-values-return-resolution-evidence-with-the-failure
  (doseq [previous [nil {:last-phase-result {}} {:last-phase-result {:value nil}}]]
    (let [result (report/resolve-inputs {:value (descriptor [:prev/last-phase-result :value] true)
                                       :literal "fixture"} previous {})
          data (:anomaly/data result)
          missing (first (filter :binding/required? (:bindings data)))]
      (is (= :invalid-input (:anomaly/type result)))
      (is (not (contains? data :inputs)))
      (is (= "fixture" (:binding/value (first (remove :binding/required? (:bindings data))))))
      (is (false? (:binding/resolved? missing)))
      (is (not (contains? missing :binding/value)))))
  (let [result (report/resolve-inputs {:explicit (descriptor [:prev/artifacts] false)
                                     :legacy [:prev/artifacts]} nil {})]
    (is (= {} (:inputs result)))
    (is (every? false? (map :binding/required? (:bindings result))))))

(deftest ^{:stratum 1} required-failure-does-not-expose-other-resolved-inputs
  (let [result (report/resolve-inputs {:missing (descriptor [:prev/artifacts] true)
                                     :alias [:prev/last-phase-result :password]}
                                    {:last-phase-result {:password "fixture-password"}} {})
        records (get-in result [:anomaly/data :bindings])]
    (is (= :invalid-input (:anomaly/type result)))
    (is (not (contains? (:anomaly/data result) :inputs)))
    (is (= (redaction/marker) (:binding/value (first (remove :binding/required? records)))))
    (is (not (str/includes? (pr-str result) "fixture-password")))))

(comment
  (descriptor [:prev/artifacts] true))
