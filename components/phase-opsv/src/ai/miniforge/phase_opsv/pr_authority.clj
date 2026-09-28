;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-authority
  "Issue scoped runtime authority and recheck it through the durable coordinator."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.actuation-decision :as decision]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.pr-transaction :as transaction]
            [ai.miniforge.phase-opsv.pr-stop :as stop]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} request [ctx prepared]
  (let [scope-keys (get-in grant/issuance-policies [:effect/pr-create :policy/scope-keys])]
    (assoc (select-keys prepared scope-keys)
           :workflow-run/status (:execution/status ctx)
           :effect/class :effect/pr-create
           :effect/preflight {:preflight/type :preflight/pr-create-readiness
                              :preflight/result :allow})))

(defn- ^{:stratum 0} authorized-commit! [runtime ctx candidate verified prepared now issued]
  (let [checked (grant/authorize issued {:effect/scope prepared :usage/count 1} now)
        mode (opsv/effective-actuation
              (assoc (decision/input ctx verified) :pr-capability-valid? (grant/authorized? checked)))]
    (cond
      (stop/stopped? runtime) (stop/revoke! runtime issued now)
      (anomaly/anomaly? mode) mode
      (= :pr-only mode) (transaction/execute! runtime ctx candidate (:opsv/decision-envelope verified) issued now)
      :else (anomaly/anomaly :unauthorized (msg/ts :pr/authority-refused) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} issue! [runtime ctx prepared now]
  (let [directory (:authority-directory runtime)
        issued (grant/issue-for-effect directory (request ctx prepared) now)]
    (flow/continue issued (partial grant/register! directory))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute! [runtime ctx candidate verified prepared now]
  (flow/continue (issue! runtime ctx prepared now)
                 (partial authorized-commit! runtime ctx candidate verified prepared now)))

(comment
  (request {} {}))
