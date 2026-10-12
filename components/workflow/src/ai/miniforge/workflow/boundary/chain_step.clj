;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-step
  "Convert failures from binding, legacy loading, and execution into child outcomes."
  (:require [ai.miniforge.workflow.chain-binding-report :as bindings]
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
      (assoc (merge (outcome/failure {:message (messages/t :chain/child-identity-conflict)})
                    (select-keys result [:execution/bindings :execution/edges]))
             :execution/id run-id)
      (assoc result :execution/id run-id))))

(defn- ^{:stratum 0} safely [operation]
  (try+ (operation)
    (catch map? error (outcome/failure error))
    (catch Exception error (outcome/failure {:message (.getMessage error)}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [step previous input]
  (safely #(bindings/resolve-inputs (:step/input-bindings step) previous input)))

(defn- ^{:stratum 1} attempt [step report opts]
  (let [result (safely #(let [loaded (loader/load-workflow (:step/workflow-id step) :latest {:skip-validation? true})]
                          (outcome/normalize (execute-loaded loaded (:inputs report) opts))))]
    (assoc result :execution/bindings (:bindings report) :execution/edges (get report :edges []))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [step report opts]
  (identified-result (:workflow-id opts)
                     (if (outcome/failed? report) report (attempt step report opts))))

(comment
  ::child-outcome-boundary)
