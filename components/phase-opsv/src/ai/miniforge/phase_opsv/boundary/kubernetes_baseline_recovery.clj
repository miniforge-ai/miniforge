;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-baseline-recovery
  "API surface class 1: fresh retained-original verification, not recovery execution or whole-trial success."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as artifacts]
            [ai.miniforge.phase-opsv.boundary.kubernetes-verification-preparation :as native]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as original]
            [ai.miniforge.phase-opsv.boundary.retained-recovery-plan :as recovery]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery :as model]
            [ai.miniforge.phase-opsv.kubernetes-baseline-recovery-schema :as schema]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration :as target]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} refused [category]
  (anomaly/anomaly category (msg/ts :recovery/baseline-verification-refused) {}))

(defn- ^{:stratum 0} observe! [input]
  (let [load (get-in input [:recovery/input :load])
        provider (dissoc (:restoration/provider input) :kubernetes/context)]
    (native/prepare! provider (:declaration load) (:request load))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish! [ctx input admitted original]
  (let [observed (observe! input)
        pin (get-in input [:recovery/input :load :request :comparison/pin])
        compared (model/compare-original pin original observed)
        report (model/report input admitted original observed compared)
        output {:opsv/baseline-recovery report}]
    (anomaly/let-ok [value (artifacts/record ctx output schema/material)]
      (let [protected-output (assoc output :opsv/baseline-recovery (:artifact/content value))]
        (confirmation/publish-with-exception-handling ctx value (artifacts/begin-publication protected-output))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify! [ctx input]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [_bounded (reporting/admit input)]
      (if-not (valid-arguments? [ctx input])
        (refused :invalid-input)
        (anomaly/let-ok [admitted (recovery/read! ctx (:recovery/input input) (:recovery/artifact-ref input))
                        baseline (original/read! ctx (get-in input [:recovery/input :load :request])
                                                 (get-in input [:recovery/input :load :comparison/artifact-ref]))]
          (if (target/provider-for-plan? (:restoration/provider input) (:recovery/plan admitted))
            (publish! ctx input admitted baseline)
            (refused :conflict)))))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread)) (throw interrupted))
    (catch exceptions/nonfatal? _ (refused :unavailable))))

(comment
  (verify! {} {}))
