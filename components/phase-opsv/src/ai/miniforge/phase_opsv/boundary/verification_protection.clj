;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-protection
  "Carry original receipt findings through protected output until immutable artifact construction."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.verification-reporting :as reporting]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :verification/unsupported-report) {}))

(defn ^{:stratum 0} stamp [prepared]
  (anomaly/let-ok [digest (artifact/content-digest (:artifact/content prepared))]
    (assoc prepared :reporting/content-hash digest)))

(defn- ^{:stratum 0} attach-material [output content-key prepared]
  (-> output
      (assoc content-key (:artifact/content prepared))
      (assoc-in [:opsv/verification-protection content-key] (dissoc prepared :artifact/content))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [value]
  (anomaly/let-ok [prepared (reporting/prepare value)] (stamp prepared)))

(defn ^{:stratum 1} attach [output prepared]
  (reduce-kv attach-material (assoc output :opsv/verification-protection {}) prepared))

(defn ^{:stratum 1} restore [prepared retained]
  (anomaly/let-ok [digest (artifact/content-digest (:artifact/content prepared))]
    (let [candidate (assoc prepared :artifact/metadata (:artifact/metadata retained))]
      (if (and (= digest (:reporting/content-hash retained))
               (evidence/valid-protected-artifact-content? candidate))
        candidate (invalid)))))

(comment (stamp {:artifact/content nil}))
