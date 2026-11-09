;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-absence
  "API surface class 1: identity-bound resource absence, never termination or recovery proof."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-absence-observation :as observation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-ownership :as ownership]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-target :as target]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as original]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-load-absence-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} from-retained! [ctx input]
  (anomaly/let-ok [admitted (retained/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                  baseline (original/read! ctx (get-in input [:recovery/input :load :request])
                                           (get-in admitted [:recovery/plan :comparison/artifact-ref]))
                  owned (ownership/admit! (:ownership/runtime input) (:recovery/plan admitted))
                  roots (target/original baseline (get-in admitted [:recovery/plan :recovery/binding :runtime/target]))]
    (observation/observe! (:settings owned) (:load/creation-record owned) roots)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe! [ctx input]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [_bounded (reporting/admit input)]
      (if (valid-arguments? [ctx input])
        (from-retained! ctx input)
        (anomaly/anomaly :invalid-input (msg/ts :load/invalid-absence-request) {})))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exceptions/nonfatal? _
      (observation/unresolved (anomaly/anomaly :unavailable (msg/ts :load/absence-unconfirmed) {})))))

(comment
  (observe! {} {}))
