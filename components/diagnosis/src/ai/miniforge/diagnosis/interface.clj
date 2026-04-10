;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.diagnosis.interface
  "Public API for the diagnosis engine.

   Extracts signals from metrics/training data, correlates symptoms,
   and generates diagnostic hypotheses with suggested improvements.

   Layer 0: Signal extraction
   Layer 1: Symptom correlation
   Layer 2: Diagnosis"
  (:require
   [ai.miniforge.diagnosis.signal :as signal]
   [ai.miniforge.diagnosis.correlator :as correlator]
   [ai.miniforge.diagnosis.engine :as engine]))

;------------------------------------------------------------------------------ Layer 0
;; Signal extraction

(def extract-signals
  "Extract improvement signals from observer/reliability/training data.

   Arguments:
     data - {:slo-checks :failure-events :training-examples :phase-metrics}
     config - optional {:min-failure-count 3 :min-examples 10 :iteration-threshold 3}

   Returns: vector of signal maps sorted by severity."
  signal/extract-signals)

;------------------------------------------------------------------------------ Layer 1
;; Symptom correlation

(def correlate-symptoms
  "Group related signals into correlated clusters.

   Returns: vector of {:correlation/signals :correlation/type
                        :correlation/confidence :correlation/hypothesis}"
  correlator/correlate-symptoms)

;------------------------------------------------------------------------------ Layer 2
;; Diagnosis

(def diagnose
  "Generate diagnostic hypotheses from correlated clusters.

   Returns: vector of {:diagnosis/id :diagnosis/hypothesis :diagnosis/confidence
                        :diagnosis/affected-heuristic :diagnosis/suggested-improvement-type}"
  engine/diagnose)

;------------------------------------------------------------------------------ Layer 3
;; Convenience: full pipeline

(defn run-diagnosis
  "Run the full diagnosis pipeline: extract → correlate → diagnose.

   Arguments:
     data   - {:slo-checks :failure-events :training-examples :phase-metrics}
     config - optional signal extraction config

   Returns: {:signals [...] :correlations [...] :diagnoses [...]}"
  [data & [config]]
  (let [signals (extract-signals data config)
        correlations (correlate-symptoms signals)
        diagnoses (diagnose correlations)]
    {:signals signals
     :correlations correlations
     :diagnoses diagnoses}))
