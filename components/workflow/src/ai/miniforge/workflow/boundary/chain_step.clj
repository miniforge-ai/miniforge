;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-step
  "Convert failures from binding, legacy loading, and execution into child outcomes."
  (:require [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner :as runner]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-loaded [loaded input opts]
  (cond
    (outcome/failure-value? loaded) (outcome/failure loaded)
    (not (map? (:workflow loaded))) (outcome/failure loaded)
    :else (runner/run-pipeline (:workflow loaded) input opts)))

(defn- ^{:stratum 0} identified-result [run-id result]
  (let [actual (:execution/id result)]
    (if (and (some? actual) (not= run-id actual))
      (assoc (outcome/failure {:message (messages/t :chain/child-identity-conflict)})
             :execution/id run-id)
      (assoc result :execution/id run-id))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} attempt [step previous input opts]
  (try+
    (let [resolved (bindings/resolve-bindings (:step/input-bindings step) previous input)
          loaded (loader/load-workflow (:step/workflow-id step) :latest {:skip-validation? true})]
      (outcome/normalize (execute-loaded loaded resolved opts)))
    (catch map? error (outcome/failure error))
    (catch Exception error (outcome/failure {:message (.getMessage error)}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [step previous input opts]
  (identified-result (:workflow-id opts) (attempt step previous input opts)))

(comment
  ::child-outcome-boundary)
