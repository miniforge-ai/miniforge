;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.verification-reporting
  "Admit complete reporting values without altering authoritative comparison inputs."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.boundary.native-material :as native]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} admit native/admit)

(defn ^{:stratum 0} prepare [value]
  (anomaly/let-ok [classified (native/classify value)
                  prepared (evidence/prepare-artifact-content (:reporting/value classified))
                  confirmed (native/confirm (:artifact/content prepared))]
    (assoc prepared :artifact/content (:reporting/value confirmed))))

(defn ^{:stratum 0} protect [value]
  (anomaly/let-ok [classified (native/classify value)
                  confirmed (native/confirm (redaction/redact (:reporting/value classified)))]
    (:reporting/value confirmed)))

(comment (admit {:ordinary true}))
