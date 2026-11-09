;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-authority
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.effect-authority :as authority]
            [ai.miniforge.phase-opsv.effect-registration :as registration]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} request [prepared]
  (let [proposal (:proposal prepared)
        keys (get-in grant/issuance-policies [:opsv/experiment :policy/scope-keys])]
    (assoc (select-keys proposal keys)
           :workflow-run/status (get-in prepared [:context :execution/status])
           :effect/class (:effect/class proposal)
           :effect/preflight {:preflight/type :preflight/opsv-staging-experiment :preflight/result :allow})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} issue! [runtime prepared now]
  (anomaly/let-ok [issued (grant/issue-for-effect (:breach-directory runtime) (request prepared) now)]
    (authority/abandon! runtime issued now (registration/register! runtime issued now))))

(comment
  (request {:proposal {} :context {:execution/status :running}}))
