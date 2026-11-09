;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-record-fixtures
  (:require [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.effect-transaction.store :as transactions]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures :as authority]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} bound [fixture proposal record issued]
  (assoc fixture :recovery/proposal proposal :recovery/record record :recovery/issued-id (:grant/id issued)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} propose! [{:keys [ctx request runtime] :as fixture} proposal issued]
  (let [options (cond-> (assoc (commit/proposal issued proposal) :proposal proposal)
                  (:effect/envelope-id fixture) (assoc :envelope-id (:effect/envelope-id fixture)))
        record (effect/propose! (:effects-directory runtime) options clock/now)]
    (is (= :proposed (:effect/state record)))
    (when (= :proposed (:effect/state record))
      (bound (assoc fixture :recovery/runtime {:context ctx :input request}) proposal record issued))))

(defn- ^{:stratum 1} replace! [fixture proposal issued]
  (let [directory (get-in fixture [:runtime :effects-directory])
        old (:recovery/record fixture)
        changed (assoc old :effect/grant-id (:grant/id issued) :effect/proposal proposal)
        stored (transactions/transition! directory old changed)]
    (is (= changed stored))
    (when (= changed stored) (bound fixture proposal stored issued))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} rebind! [fixture proposal]
  (when-let [issued (authority/issue! fixture proposal)]
    (replace! fixture proposal issued)))

(comment
  (fn [fixture] (rebind! fixture (:recovery/proposal fixture))))
