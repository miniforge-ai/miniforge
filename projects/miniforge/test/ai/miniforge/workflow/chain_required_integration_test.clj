;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-required-integration-test
  (:require [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.workflow.chain-edge-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} required [field]
  {:binding/expression [:prev/last-phase-result field] :binding/required? true})

(defn- ^{:stratum 0} modes [opts]
  [opts (dissoc opts :chain/event-stream :chain/retention-directory)])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unresolved-required-input-fails-edge-before-dependent-loading
  (support/with-fixture
   (fn [opts _ stream]
     (let [declarations {:value (required :missing) :alias [:prev/last-phase-result :password]}
           calls (atom []) loads (atom [])
           result (support/run-chain opts declarations :completed calls loads)
           edge (first (:chain/edges result))
           records (:edge/bindings edge)
           missing (first (filter :binding/required? records))
           resolved (first (remove :binding/required? records))
           projection (evidence/create-chain-evidence (support/definition declarations) result)]
       (is (= :failed (:chain/status result) (:edge/status edge)))
       (is (= 1 (count @calls) (count @loads)))
       (is (= 2 (:edge/bindings-count edge) (count records)))
       (is (false? (:binding/resolved? missing)))
       (is (not (contains? missing :binding/value)))
       (is (= (redaction/marker) (:binding/value resolved)))
       (is (every? #(= (:id (first @calls)) (:binding/source-workflow-id %)) records))
       (is (= (mapv :step/execution-id (:chain/step-results result))
              [(:edge/from-workflow-id edge) (:edge/to-workflow-id edge)]))
       (is (= (:chain/edges result) (:chain/edges projection)))
       (is (not (str/includes? (pr-str projection) "fixture-password")))
       (is (= [:chain/started :chain/step-started :chain/step-completed :chain/step-started
               :chain.edge/started :chain.edge/failed :chain/step-failed :chain/failed]
              (support/types stream)))))))

(deftest ^{:stratum 1} resolved-false-completes-and-retains-the-required-declaration
  (support/with-fixture
   (fn [opts _ stream]
     (let [declarations {:value (required :flag)} calls (atom [])
           result (support/run-chain opts declarations :completed calls (atom []))
           record (first (:edge/bindings (first (:chain/edges result))))]
       (is (= :completed (:chain/status result)))
       (is (= {:value false} (:input (second @calls))))
       (is (true? (:binding/required? record)))
       (is (true? (:binding/resolved? record)))
       (is (false? (:binding/value record)))
       (is (= declarations (get-in result [:chain/definition-snapshot :chain/steps 1 :step/input-bindings])))
       (is (= :chain/completed (last (support/types stream))))))))

(deftest ^{:stratum 1} required-predecessor-without-a-source-fails-without-inventing-an-edge
  (support/with-fixture
   (fn [opts _ _stream]
     (doseq [options (modes opts)]
       (let [definition (update (support/definition {:value (required :missing)})
                                :chain/steps #(vector (second %)))
             calls (atom []) loads (atom [])
             result (support/run-definition definition options :completed calls loads)]
         (is (= :failed (:chain/status result)))
         (is (= [] (:chain/edges result)))
         (is (empty? @calls))
         (is (empty? @loads))
         (is (false? (:binding/resolved? (first (:step/bindings (first (:chain/step-results result))))))))))))

(deftest ^{:stratum 1} legacy-required-failure-also-prevents-dependent-loading
  (support/with-fixture
   (fn [opts _ _stream]
     (let [calls (atom []) loads (atom [])
           result (support/run-chain (second (modes opts)) {:value (required :missing)}
                                     :completed calls loads)]
       (is (= :failed (:chain/status result)))
       (is (= [] (:chain/edges result)))
       (is (= 1 (count @calls) (count @loads)))))))

(comment
  (required :artifact))
