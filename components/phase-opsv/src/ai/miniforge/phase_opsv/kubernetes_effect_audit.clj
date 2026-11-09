;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-effect-audit
  "Correlate native durable dispositions without altering the canonical effect proposal."
  (:require [ai.miniforge.phase-opsv.effect-disposition :as disposition]
            [ai.miniforge.phase-opsv.kubernetes-effect-audit-schema :as schema]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-record? (m/validator schema/NativeRecord))

(defn ^{:stratum 0} payload [record decision]
  (disposition/payload record decision schema/observed-keys))

(defn ^{:stratum 0} material [record decision]
  {:opsv/native-effect-disposition {:effect/transaction record :opsv/decision-envelope decision}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} correlated? [ctx id decision record assembly]
  (let [proposal (:effect/proposal record)
        workflow-id (context/workflow-id ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])]
    (and (valid-record? record)
         (or (= :granted (:effect/authority record))
             (and (#{:proposed :failed} (:effect/state record)) (empty? (:effect/observed record))))
         (= id (:effect/id record) (:effect/id proposal))
         (= (:effect/class record) (:effect/class proposal))
         (= (:effect/envelope-id record) (:envelope/id decision))
         (= workflow-id (:workflow-run/id proposal) (:evidence-bundle/workflow-id assembly))
         (= bundle-id (:evidence-bundle/id assembly))
         (= :assembling (:opsv.assembly/status assembly))
         (or (not (#{:proposed :committing} (:effect/state record))) (empty? (:effect/observed record))))))

(comment
  (payload {} {}))
