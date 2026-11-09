;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.kubernetes-forward-execution
  "Internal host composition; operation functions are code-owned, never serialized authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-preparation :as preparation]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-authority :as issuance]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-result :as result]
            [ai.miniforge.phase-opsv.boundary.kubernetes-forward-transaction :as transaction]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.exception-policy :as exceptions]
            [ai.miniforge.phase-opsv.messages :as msg]
            [slingshot.slingshot :refer [try+]])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :experiment/native-start-runtime-invalid) {}))

(defn- ^{:stratum 0} cleanup-failures [throwable]
  (mapv (partial anomaly/exception-anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed))
        (.getSuppressed ^Throwable throwable)))

(defn- ^{:stratum 0} commit-prepared! [kind runtime input prepared now]
  (anomaly/let-ok [issued (issuance/issue! runtime prepared now)]
    (result/complete! runtime input issued now (partial transaction/execute! kind runtime prepared input issued now))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} execute! [kind runtime input]
  (anomaly/let-ok [prepared (preparation/prepare! kind runtime input)]
    (let [now ((:clock runtime))]
      (if (instance? Instant now)
        (commit-prepared! kind runtime input prepared now)
        (invalid)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-forward! [kind runtime input]
  (try+
    (when (.isInterrupted (Thread/currentThread)) (throw (InterruptedException.)))
    (anomaly/let-ok [_bounded (reporting/admit input)]
      (if ((:valid-arguments? kind) [runtime input])
        (let [result (execute! kind runtime input)]
          (when (anomaly/anomaly? result) (result/fence! runtime input))
          result)
        (invalid)))
    (catch InterruptedException interrupted
      (result/fence! runtime input) (.interrupt (Thread/currentThread)) (throw interrupted))
    (catch Error fatal (result/fence! runtime input) (throw fatal))
    (catch exceptions/nonfatal? _
      (let [failures (cleanup-failures (:throwable &throw-context))]
        (result/fence! runtime input)
        (anomaly/exception-anomaly :unavailable (msg/ts :experiment/native-start-unconfirmed)
          {:effect/id ((:effect-id kind) input)
           :grant/cleanup-failures failures} (:throwable &throw-context))))))

(comment
  (execute-forward! {} {} {}))
