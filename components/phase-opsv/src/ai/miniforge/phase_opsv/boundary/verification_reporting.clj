;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-reporting
  "Admit complete reporting values without altering authoritative comparison inputs."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invalid []
  (anomaly/anomaly :invalid-input (msg/ts :verification/unsupported-report) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admit [value]
  (if (redaction/supported? value) {:reporting/value value} (invalid)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} protect [value]
  (anomaly/let-ok [admitted (admit value)] (redaction/redact (:reporting/value admitted))))
