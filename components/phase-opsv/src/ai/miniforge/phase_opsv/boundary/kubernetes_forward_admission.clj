;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-admission
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.phase-opsv.boundary.experiment-preflight :as preflight]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as original]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as recovery]
            [ai.miniforge.phase-opsv.kubernetes-forward-runtime-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.run-control :as control]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-context? (m/validator schema/Context))

(def ^{:stratum 0 :private true} allowing? (m/validator decision/AllowingEnvelope))

(defn ^{:stratum 0} refused []
  (anomaly/anomaly :unauthorized (msg/ts :experiment/native-start-refused) {}))

(defn ^{:stratum 0} snapshot [ctx]
  ;; Journal progress can advance without changing the run's safety configuration.
  (update-in (select-keys ctx [:execution/id :execution/status :execution/input :execution/opts])
             [:execution/opts :opsv/governance] dissoc :policy/event-watermark))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} current-context! [runtime]
  (anomaly/let-ok [ctx ((:current-context runtime))]
    (if (and (valid-context? ctx)
             (not (true? (get-in ctx [:execution/input :opsv/safe-mode?])))
             (control/bound-to? (:control runtime) (:execution/id ctx)
                               (:authority-directory runtime) (:fence runtime))
             (not (control/stopped? (:control runtime))))
      ctx
      (refused))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} readiness! [runtime input]
  (anomaly/let-ok [ctx (current-context! runtime)
                  admitted (recovery/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                  baseline (original/read! ctx (get-in input [:recovery/input :load :request])
                                           (get-in admitted [:recovery/plan :comparison/artifact-ref]))
                  evaluated (preflight/evaluate ctx (get-in input [:recovery/input :load :request]) baseline)]
    (let [envelope (:opsv/experiment-decision-envelope evaluated)]
      (if-not (allowing? envelope)
        (assoc-in (refused) [:anomaly/data :opsv/experiment-admission] evaluated)
        {:context ctx
         :decision envelope
         :recovery/plan (:recovery/plan admitted)
         :recovery/hash (:recovery/hash admitted)
         :comparison/original baseline}))))

(comment
  (snapshot {:execution/id (random-uuid)}))
