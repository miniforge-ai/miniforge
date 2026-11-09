;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-proposal-fixtures
  (:require [ai.miniforge.execution-grant.opsv-scope-fixtures :as scope]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as native]
            [ai.miniforge.phase-opsv.retained-preparation-fixtures :as retained]
            [ai.miniforge.phase-opsv.test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} limits []
  (assoc (select-keys (scope/experiment-binding :trial-apply)
                      [:kubernetes/context :opsv/traffic-destinations :opsv/duration-ms :opsv/load-limits])
         :effect/id (random-uuid)))

(defn- ^{:stratum 0} bind-workflow [ctx native]
  (let [workflow-id (:execution/id ctx)
        contract (get-in native [:request :comparison/pin :comparison/contract])
        pin (phase/pin-verification-environment (assoc-in contract [:comparison/binding :workflow/id] workflow-id))]
    (-> native
        (assoc-in [:declaration :workflow/id] workflow-id)
        (assoc-in [:request :comparison/pin] pin))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} with-context [produce f ctx directory]
  (let [ctx (storage/step ctx (first support/handlers))
        native (bind-workflow ctx (produce))
        content {:comparison/pin (get-in native [:request :comparison/pin])
                 :comparison/preparation (:baseline native)}
        value (retained/record ctx content)
        id (retained/publish! ctx value)
        input (assoc (select-keys native [:declaration :request :current :parameters])
                     :comparison/artifact-ref id :limits (limits))]
    (f {:ctx ctx :directory directory :native native :id id :input input :value value})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-input
  ([f] (with-input native/input f))
  ([produce f] (storage/with-context (partial with-context produce f))))

(comment ::real-native-acquisition-publication-and-acknowledgement-no-invented-baseline)
