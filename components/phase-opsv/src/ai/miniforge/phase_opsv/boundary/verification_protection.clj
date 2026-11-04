;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-protection
  "Carry original receipt findings through protected output until immutable artifact construction."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :verification/unsupported-report) {}))

(defn ^{:stratum 0} stamp [prepared]
  (anomaly/let-ok [digest (artifact/content-digest (:artifact/content prepared))]
    (assoc prepared :reporting/content-hash digest)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore [prepared retained]
  (anomaly/let-ok [digest (artifact/content-digest (:artifact/content prepared))]
    (let [candidate (assoc prepared :artifact/metadata (:artifact/metadata retained))]
      (if (and (= digest (:reporting/content-hash retained))
               (evidence/valid-protected-artifact-content? candidate))
        candidate (invalid)))))

(comment (stamp {:artifact/content nil}))
