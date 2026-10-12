;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-child-identity-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-execution-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner :as runner]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} complete [_definition _input opts]
  {:execution/id (:workflow-id opts) :execution/status :completed})

(defn- ^{:stratum 0} record-options [calls execute definition input opts]
  (swap! calls conj opts)
  (execute definition input opts))

(defn- ^{:stratum 0} step-events [events]
  (filterv :step/id events))

(defn- ^{:stratum 0} execution-ids [outcome]
  (mapv :step/execution-id (:chain/step-results outcome)))

(defn- ^{:stratum 0} conflicting-child [& _]
  {:execution/id (random-uuid) :execution/status :completed})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} observed [definition opts load-child execute]
  (let [stream (events/create-event-stream {:sinks []})
        calls (atom [])]
    (with-redefs [loader/load-workflow load-child
                  runner/run-pipeline (partial record-options calls execute)]
      (let [outcome (workflow/run-chain definition {} (assoc opts :event-stream stream))
            history (events/get-events stream {})]
        {:outcome outcome :events history :calls @calls}))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} child-identities-override-parent-id-and-retain-correlation
  (let [parent (random-uuid)
        correlation (random-uuid)
        opts {:workflow-id parent :workflow-run/correlation-id correlation}
        {:keys [outcome events calls]} (observed (support/definition) opts support/loaded-workflow complete)
        ids (execution-ids outcome)
        observed-ids (mapv :workflow/id (step-events events))]
    (is (= :completed (:chain/status outcome)))
    (is (= 2 (count ids)))
    (is (every? uuid? ids))
    (is (= (count ids) (count (set ids))))
    (is (not (contains? (set ids) parent)))
    (is (= ids (mapv :workflow-id calls)))
    (is (= [correlation correlation] (mapv :workflow-run/correlation-id calls)))
    (is (= (mapcat #(repeat 2 %) ids) observed-ids))
    (is (= [0 1 0 1] (mapv :event/sequence-number (step-events events))))))

(deftest ^{:stratum 2} fresh-invocations-do-not-reuse-child-identities
  (let [definition (support/definition)
        first-run (observed definition {} support/loaded-workflow complete)
        next-run (observed definition {} support/loaded-workflow complete)
        ids (mapcat (comp execution-ids :outcome) [first-run next-run])]
    (is (= 4 (count (set ids))))))

(deftest ^{:stratum 2} early-failures-retain-the-started-child-identity
  (let [definition (support/definition)
        bad-bindings (assoc-in definition [:chain/steps 0 :step/input-bindings] 42)]
    (doseq [[input load-child] [[definition support/missing-workflow]
                               [bad-bindings support/loaded-workflow]]]
      (let [{:keys [outcome events calls]} (observed input {} load-child complete)
            ids (execution-ids outcome)
            step-history (step-events events)]
        (is (= :failed (:chain/status outcome)))
        (is (empty? calls))
        (is (= 1 (count ids)))
        (is (uuid? (first ids)))
        (is (= [:chain/step-started :chain/step-failed] (mapv :event/type step-history)))
        (is (= [(first ids) (first ids)] (mapv :workflow/id step-history)))
        (is (= [0 1] (mapv :event/sequence-number step-history)))))))

(deftest ^{:stratum 2} conflicting-child-result-cannot-advance-the-chain
  (let [{:keys [outcome events calls]} (observed (support/definition) {} support/loaded-workflow conflicting-child)
        step-history (step-events events)
        child-id (:workflow-id (first calls))]
    (is (= :failed (:chain/status outcome)))
    (is (= 1 (count calls)))
    (is (= [child-id] (execution-ids outcome)))
    (is (= [child-id child-id] (mapv :workflow/id step-history)))
    (is (= (messages/t :chain/child-identity-conflict) (:chain/error (last step-history))))))

(comment
  (support/definition))
