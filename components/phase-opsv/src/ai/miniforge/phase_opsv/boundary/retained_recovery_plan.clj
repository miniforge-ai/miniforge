;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.retained-recovery-plan
  "API surface class 1: uncached producer/acknowledgement proof plus fresh independent N4 admission."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.recovery-plan-admission :as admission]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exception-policy]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.recovery-plan-material :as material]
            [ai.miniforge.phase-opsv.recovery-plan-material-schema :as schema]
            [ai.miniforge.phase-opsv.retained-preparation :as original]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as retained]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/ReadArguments))

(def ^{:stratum 0 :private true} valid-assembly? (m/validator retained/Assembly))

(defn- ^{:stratum 0} refused [category]
  (anomaly/anomaly category (msg/ts :recovery/retained-plan-required) {}))

(defn- ^{:stratum 0} check-interruption! []
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-admitted! [ctx admitted id]
  (check-interruption!)
  (let [value (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) id)
        _interruption (check-interruption!)
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                            (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (cond
      (= :fatal (:anomaly/type value)) (refused :fatal)
      (or (nil? value) (anomaly/anomaly? value)) (refused :unavailable)
      (and (valid-assembly? assembly) (original/acknowledged? ctx assembly id)
           (material/valid? ctx admitted id value))
      (material/reference id (:artifact/content value))
      :else (refused :conflict))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read! [ctx input id]
  (try+
    (check-interruption!)
    (anomaly/let-ok [_bounded (reporting/admit [input id])]
      (if-not (valid-arguments? [ctx id])
        (refused :invalid-input)
        (anomaly/let-ok [admitted (admission/admit! ctx input)]
          (if (material/allowed? admitted) (read-admitted! ctx admitted id) (refused :unauthorized)))))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exception-policy/nonfatal? _ (refused :unavailable))))

(comment ::no-old-envelope-returned-as-effect-authority-no-stale-approval-cache)
