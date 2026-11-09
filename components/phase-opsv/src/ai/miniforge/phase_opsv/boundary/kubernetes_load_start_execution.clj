;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-load-start-execution
  "API surface class 1: trusted-host orchestration of one exact governed load start."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-preparation :as preparation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-authority :as issuance]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-result :as result]
            [ai.miniforge.phase-opsv.boundary.kubernetes-load-start-transaction :as transaction]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.kubernetes-load-start-runtime-schema :as schema]
            [ai.miniforge.phase-opsv.messages :as msg]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-arguments? (m/validator schema/Arguments))

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :experiment/native-start-runtime-invalid) {}))

(defn- ^{:stratum 0} commit-prepared! [runtime input prepared now]
  (anomaly/let-ok [issued (issuance/issue! runtime prepared now)]
    (result/complete! runtime input issued now (partial transaction/execute! runtime prepared input issued now))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} execute! [runtime input]
  (anomaly/let-ok [prepared (preparation/prepare! runtime input)]
    (let [now ((:clock runtime))]
      (if (instance? Instant now)
        (commit-prepared! runtime input prepared now)
        (invalid)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start! [runtime input]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [_bounded (reporting/admit input)]
      (if (valid-arguments? [runtime input])
        (let [result (execute! runtime input)]
          (when (anomaly/anomaly? result) (result/fence! runtime input))
          result)
        (invalid)))
    (catch InterruptedException interrupted
      (result/fence! runtime input) (.interrupt (Thread/currentThread)) (throw interrupted))
    (catch Error fatal (result/fence! runtime input) (throw fatal))
    (catch exceptions/nonfatal? _
      (let [cleanup-failures (mapv (partial anomaly/exception-anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed))
                                   (.getSuppressed ^Throwable (:throwable &throw-context)))]
        (result/fence! runtime input)
        (anomaly/exception-anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed)
          {:effect/id (:effect/id input) :grant/cleanup-failures cleanup-failures} (:throwable &throw-context))))))

(comment
  (start! {} {}))
