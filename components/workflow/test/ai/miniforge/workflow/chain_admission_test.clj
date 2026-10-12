;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-admission-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-execution-test-support :as execution]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} capture [calls definition & _]
  (swap! calls conj definition)
  (:chain/run-record definition))

(defn- ^{:stratum 0} complete [_definition _input opts]
  {:execution/id (:workflow-id opts) :execution/status :completed})

(defn- ^{:stratum 0} invalid-definitions [definition]
  [nil [] {} (dissoc definition :chain/version)
   (assoc definition :chain/version "latest")
   (assoc definition :chain/version :latest)
   (assoc definition :chain/version " ")
   (assoc definition :chain/run-id (random-uuid))
   (assoc definition :chain/steps (repeat 2 (first (:chain/steps definition))))
   (update definition :chain/steps into (:chain/steps definition))
   (assoc-in definition [:chain/steps 0 :step/input-bindings] {:task (atom :mutable)})])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} invalid-definitions-do-not-allocate-or-execute
  (let [calls (atom [])
        definition (support/definition :example "1.0.0")]
    (with-redefs [composition/run-record (partial capture calls)
                  chain/run-chain (partial capture calls)]
      (doseq [invalid (invalid-definitions definition)]
        (is (anomaly/any-anomaly? (workflow/run-chain invalid {} {}))))
      (is (empty? @calls)))))

(deftest ^{:stratum 1} each-invocation-retains-resolved-composition-and-fresh-identity
  (let [calls (atom [])
        definition (support/definition :example "1.0.0")]
    (with-redefs [chain/run-chain (partial capture calls)]
      (let [first-run (workflow/run-chain definition {} {})
            next-run (workflow/run-chain definition {} {})
            snapshot (:chain/definition-snapshot first-run)]
        (is (uuid? (:chain/run-id first-run)))
        (is (not= (:chain/run-id first-run) (:chain/run-id next-run)))
        (is (= :example (:chain/definition-id first-run) (:chain/definition-id snapshot)))
        (is (= "1.0.0" (:chain/definition-version first-run) (:chain/definition-version snapshot)))
        (is (= (:chain/steps definition) (:chain/steps snapshot)))
        (is (= snapshot (:chain/definition-snapshot next-run)))
        (is (not (contains? snapshot :chain/id)))))))

(deftest ^{:stratum 1} mutable-metadata-is-not-retained-in-composition
  (let [calls (atom [])
        metadata {:mutable (atom :original)}
        path (with-meta [:prev/last-phase-result :plan] metadata)
        bindings (with-meta {:task path} metadata)
        definition (assoc-in (support/definition :example "1.0.0")
                             [:chain/steps 0 :step/input-bindings] bindings)
        marked (with-meta definition metadata)]
    (with-redefs [chain/run-chain (partial capture calls)]
      (let [snapshot (:chain/definition-snapshot (workflow/run-chain marked {} {}))
            retained (get-in snapshot [:chain/steps 0 :step/input-bindings])]
        (is (= path (:task retained)))
        (is (nil? (meta snapshot)))
        (is (nil? (meta retained)))
        (is (nil? (meta (:task retained))))))))

(deftest ^{:stratum 1} admission-tolerates-key-comparators-without-retaining-them
  (let [calls (atom [])
        definition (into (sorted-map) (support/definition :example "1.0.0"))]
    (with-redefs [chain/run-chain (partial capture calls)]
      (is (uuid? (:chain/run-id (workflow/run-chain definition {} {}))))
      (is (anomaly/any-anomaly? (workflow/run-chain (sorted-map "unexpected" true) {} {}))))))

(deftest ^{:stratum 1} real-chain-result-retains-admission-on-child-failure
  (let [stream (events/create-event-stream {:sinks []})]
    (with-redefs [loader/load-workflow execution/missing-workflow
                  runner/run-pipeline complete]
      (let [result (workflow/run-chain (execution/definition) {} {:event-stream stream})]
        (is (= :failed (:chain/status result)))
        (is (uuid? (:chain/run-id result)))
        (is (= :outcome-test (:chain/definition-id result)))
        (is (= "1.0.0" (:chain/definition-version result)))
        (is (= (:chain/steps (execution/definition))
               (get-in result [:chain/definition-snapshot :chain/steps])))))))

(comment
  (support/definition :example "1.0.0"))
