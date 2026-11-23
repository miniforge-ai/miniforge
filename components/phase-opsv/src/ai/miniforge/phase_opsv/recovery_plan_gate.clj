;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-gate
  "N4 adapter for independent approval of one exact bounded recovery plan."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-plan? (m/validator plan/Plan))

(defn- ^{:stratum 0} refused []
  {:passed? false
   :errors [{:type :opsv/gate-failed :gate/id :recovery-plan
             :reason-code :recovery/approval-required
             :message (msg/ts :recovery/approval-required)
             :remediation {:action :approve-exact-recovery-plan
                           :summary (msg/ts :recovery/approval-remediation)
                           :details {:approval/source :runtime-owned :approval/scope :exact-plan-hash}}}]})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check [_pack ctx]
  (let [{value :recovery/plan digest :recovery/hash} (:opsv/recovery-plan ctx)
        approved (get-in ctx [:opsv/recovery-admission :recovery/approved-plan-hashes])]
    (if (and (valid-plan? value) (= digest (hash/content-hash value))
             (set? approved) (contains? approved digest))
      {:passed? true :errors []}
      (refused))))

;------------------------------------------------------------------------------ Layer 2

(defmethod ^{:stratum 2} gate/get-gate :opsv/recovery-plan-gate [_]
  {:name :opsv/recovery-plan-gate
   :description (msg/ts :recovery/approval-description)
   :check check :repair nil})

(gate/register-gate! :opsv/recovery-plan-gate)

(comment ::ordinary-gate-engine-produces-the-decision-envelope)
