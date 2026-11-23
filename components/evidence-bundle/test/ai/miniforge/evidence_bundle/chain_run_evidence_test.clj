;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.chain-run-evidence-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition [steps]
  {:chain/id :retained :chain/version "1.0.0" :chain/steps steps})

(defn- ^{:stratum 0} step [id]
  {:step/id id :step/workflow-id :fixture})

(defn- ^{:stratum 0} step-result [definition status]
  (assoc definition :step/execution-id (random-uuid) :step/status status))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} upstream-errors-do-not-become-synthetic-execution-evidence
  (doseq [error [(anomaly/anomaly :unavailable "Publication failed" {:chain/run-id (random-uuid)})
                 {:anomaly/category :anomalies/unavailable}]]
    (is (identical? error (evidence/create-chain-evidence (definition []) error)))))

(defn- ^{:stratum 1} failed-result []
  (let [steps [(step :first) (step :dependent)]
        snapshot {:chain/definition-id :retained :chain/definition-version "1.0.0" :chain/steps steps}
        results [(step-result (first steps) :failed)]]
    (merge (select-keys snapshot [:chain/definition-id :chain/definition-version])
           {:chain/run-id (random-uuid)
            :chain/definition-snapshot snapshot
            :chain/status :failed
            :chain/step-count (count steps)
            :chain/step-results results
            :chain/duration-ms 0})))

(deftest ^{:stratum 1} fresh-admissions-remain-distinct-in-evidence
  (let [definition (definition [])
        first-run (workflow/run-chain definition {} {})
        second-run (workflow/run-chain definition {} {})
        first-evidence (evidence/create-chain-evidence definition first-run)
        second-evidence (evidence/create-chain-evidence definition second-run)]
    (is (uuid? (:chain/run-id first-evidence)))
    (is (= (:chain/run-id first-run) (:chain/run-id first-evidence)))
    (is (= (:chain/run-id second-run) (:chain/run-id second-evidence)))
    (is (not= (:chain/run-id first-evidence) (:chain/run-id second-evidence)))
    (is (= (:chain/definition-snapshot first-run) (:chain/definition-snapshot first-evidence)))
    (is (= 0 (:chain/step-count first-evidence)))))

(deftest ^{:stratum 1} legacy-evidence-does-not-invent-run-or-child-identities
  (let [result {:chain/status :completed :chain/step-results [(step :legacy)]}
        projection (evidence/create-chain-evidence (definition []) result)
        summary (first (:chain-evidence/step-summaries projection))]
    (is (= :retained (:chain-evidence/chain-id projection)))
    (is (not (contains? projection :chain/run-id)))
    (is (not (contains? projection :chain/definition-snapshot)))
    (is (not (contains? summary :step/execution-id)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} early-failure-retains-admission-and-the-started-child-only
  (let [result (failed-result)
        stale-definition {:chain/id :wrong :chain/version "wrong" :chain/steps []}
        projection (evidence/create-chain-evidence stale-definition result)
        summary (first (:chain-evidence/step-summaries projection))]
    (is (= :retained (:chain/definition-id projection)))
    (is (= "1.0.0" (:chain/definition-version projection)))
    (is (= (:chain/definition-snapshot result) (:chain/definition-snapshot projection)))
    (is (= (:chain/run-id result) (:chain/run-id projection)))
    (is (= (:step/execution-id (first (:chain/step-results result))) (:step/execution-id summary)))
    (is (= 2 (:chain/step-count projection)))
    (is (= 1 (:chain-evidence/step-count projection)))
    (is (= 1 (count (:chain-evidence/step-summaries projection))))
    (is (= :failed (:chain-evidence/status projection)))
    (is (= :retained (:chain-evidence/chain-id projection)))
    (is (= "1.0.0" (:chain-evidence/chain-version projection)))
    (is (not (contains? projection :chain/edges)))))

(comment
  (failed-result))
