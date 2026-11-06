;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.retained-preparation
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exception-policy]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.retained-preparation-schema :as schema]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid-arguments? (m/validator schema/Arguments))

(def ^{:stratum 0} ^:private valid-content? (m/validator schema/Content))

(def ^{:stratum 0} ^:private valid-assembly? (m/validator schema/Assembly))

(defn- ^{:stratum 0} refused [category]
  (anomaly/anomaly category (msg/ts :verification/retained-preparation-required) {}))

(defn- ^{:stratum 0} check-interruption! []
  (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-admitted! [ctx request id]
  (check-interruption!)
  (let [value (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) id)
        _interruption (check-interruption!)
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                            (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (cond
      (= :fatal (:anomaly/type value)) (refused :fatal)
      (or (nil? value) (anomaly/anomaly? value)) (refused :unavailable)
      (and (valid-assembly? assembly) (retained/acknowledged? ctx assembly id)
           (valid-content? (:artifact/content value)) (retained/valid? ctx request id value))
      (get-in value [:artifact/content :comparison/preparation])
      :else (refused :conflict))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read! [ctx request id]
  (try+
    (check-interruption!)
    (anomaly/let-ok [_bounded (reporting/admit request)]
      (if (valid-arguments? [ctx request id])
        (read-admitted! ctx request id)
        (refused :invalid-input)))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch exception-policy/nonfatal? _ (refused :unavailable))))

(comment ::uncached-immutable-read-with-current-acknowledgement-no-permission-granted)
