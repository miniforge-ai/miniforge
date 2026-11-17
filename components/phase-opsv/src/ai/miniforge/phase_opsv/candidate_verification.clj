;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.candidate-verification
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.verification-adapter :as adapter]
            [ai.miniforge.phase-opsv.boundary.retained-preparation :as retained]
            [ai.miniforge.phase-opsv.boundary.verification-receipt :as receipts]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.protocol :as port]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [ai.miniforge.phase-opsv.verification-drift :as drift]
            [ai.miniforge.phase-opsv.verification-environment :as environment]
            [ai.miniforge.phase-opsv.verification-environment-artifacts :as artifacts]
            [ai.miniforge.phase-opsv.verification-environment-schema :as schema]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare [ctx request]
  (let [runtime (context/adapter ctx)]
    (if-not (satisfies? port/VerificationPreparationAdapter runtime)
      (anomaly/anomaly :unavailable (msg/ts :verification/preparation-missing) {})
      (receipts/admit schema/PreparationReceipt :verification/preparation-invalid request
                      (adapter/invoke port/prepare-verification runtime request)))))

(defn- ^{:stratum 0} compare-observation [pin digest stage receipt]
  (let [result (environment/compare-observation pin digest stage receipt)]
    (if (anomaly/anomaly? result) (drift/comparison-mismatch result receipt) result)))

(defn- ^{:stratum 0} with-output [result output]
  (if (anomaly/anomaly? result)
    (assoc-in result [:anomaly/data :opsv/phase-output] output)
    (assoc result :verification/output output)))

(defn- ^{:stratum 0} preparation-request [request pin]
  (assoc request :comparison/pin pin :runtime/target
         (get-in pin [:comparison/contract :comparison/binding :runtime/target])))

(defn- ^{:stratum 0} measure [ctx state]
  (let [id (get-in state [:output :opsv/phase-artifact-ids :verification-environment])
        request (assoc (:request state) :comparison/artifact-ref id)]
    (anomaly/let-ok [_ready (artifacts/ready ctx)
                    _retained (retained/read! ctx request id)]
      (receipts/admit schema/Receipt :verification/invalid request
                      (adapter/invoke port/run-verification (context/adapter ctx) request)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retain-preparation! [ctx synthesized request]
  (anomaly/let-ok [pin (environment/pin ctx (:opsv/environment-fingerprint synthesized) request)
                  _ready (artifacts/ready ctx)
                  request (preparation-request request pin)
                  prepared (prepare ctx request)
                  retained (artifacts/retain! ctx synthesized
                             {:comparison/pin pin :comparison/preparation prepared})]
    {:pin pin :digest (:comparison/hash pin) :output retained :request request :prepared prepared}))

(defn- ^{:stratum 1} comparison [state stage measured]
  {:comparison/stage stage
   :comparison/observation measured
   :comparison/outcome (flow/continue measured
                         (partial compare-observation (:pin state) (:digest state) stage))})

(defn- ^{:stratum 1} complete [ctx state compared]
  (let [output (:output state)
        measured (:comparison/observation compared)
        matched (:comparison/outcome compared)
        content (merge (:opsv/verification-environment output) compared
                  {:comparison/preparation-artifact-ref
                   (get-in output [:opsv/phase-artifact-ids :verification-environment])})
        retained (artifacts/retain! ctx output content)]
    (if (anomaly/anomaly? retained)
      (cond-> retained
        (get-in matched [:anomaly/data :opsv/environment-drift])
        (assoc-in [:anomaly/data :opsv/environment-drift]
                  (redaction/redact (get-in matched [:anomaly/data :opsv/environment-drift]))))
      ;; Keep the successful receipt in memory until original criteria are evaluated.
      (with-output (if (anomaly/anomaly? matched) (redaction/redact matched) measured) retained))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute [ctx synthesized request]
  (anomaly/let-ok [state (retain-preparation! ctx synthesized request)]
    ;; Recovery expectations constrain the fresh baseline; this executes no rollback.
    (let [baseline (comparison state :rollback (:prepared state))
          compared (if (anomaly/anomaly? (:comparison/outcome baseline))
                     baseline
                     (comparison state :candidate (measure ctx state)))]
      (complete ctx state compared))))

(comment ::contract-is-retained-before-the-candidate-callback-not-learned-from-it)
