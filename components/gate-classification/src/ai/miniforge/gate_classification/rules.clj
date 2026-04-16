;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.gate-classification.rules
  "Deterministic classification rules engine.

   Three core rules applied in sequence:
   1. Low Confidence Rule: confidence < threshold -> escalate to needs-investigation
   2. Documented False-Positive Rule: false-positive + documented + high confidence -> auto-exclude
   3. True-Positive Passthrough Rule: true-positive + high confidence -> confirmed"
  (:require
   [ai.miniforge.gate-classification.config :as config]))

;; -------------------------------------------------------------------------- Layer 0
;; Predicates

(defn true-positive?
  "Check if violation is classified as true-positive."
  [violation]
  (= :true-positive (:classification/category violation)))

(defn false-positive?
  "Check if violation is classified as false-positive."
  [violation]
  (= :false-positive (:classification/category violation)))

(defn needs-investigation?
  "Check if violation needs investigation."
  [violation]
  (= :needs-investigation (:classification/category violation)))

(defn documented?
  "Check if violation has documented status."
  [violation]
  (= :documented (:classification/doc-status violation)))

(defn get-confidence
  "Extract confidence from violation, defaulting to 0.5."
  [violation]
  (get violation :classification/confidence 0.5))

;; -------------------------------------------------------------------------- Layer 1
;; Individual rules

(defn apply-low-confidence-rule
  "If confidence < threshold, escalate to needs-investigation.
   Overrides LLM judgment when confidence is too low to trust."
  [violation config]
  (let [threshold  (get config :confidence-threshold 0.5)
        confidence (get-confidence violation)]
    (if (and (< confidence threshold)
             (not (needs-investigation? violation)))
      (-> violation
          (assoc :classification/category :needs-investigation)
          (assoc :classification/rule-applied :low-confidence))
      violation)))

(defn apply-documented-fp-rule
  "If false-positive + documented + high confidence -> confirm as auto-exclude."
  [violation config]
  (let [doc-threshold (get config :doc-status-threshold 0.7)
        confidence    (get-confidence violation)]
    (if (and (false-positive? violation)
             (documented? violation)
             (>= confidence doc-threshold))
      (assoc violation :classification/rule-applied :documented-false-positive)
      violation)))

(defn apply-true-positive-rule
  "If true-positive + high confidence -> confirmed as real violation."
  [violation config]
  (let [tp-threshold (get config :true-positive-threshold 0.8)
        confidence   (get-confidence violation)]
    (if (and (true-positive? violation)
             (>= confidence tp-threshold))
      (assoc violation :classification/rule-applied :confirmed-true-positive)
      violation)))

;; -------------------------------------------------------------------------- Layer 2
;; Batch processing

(defn apply-rules
  "Apply all deterministic rules to a single violation.
   Rules are applied in sequence: low-confidence -> documented-FP -> TP."
  [violation config]
  (-> violation
      (apply-low-confidence-rule config)
      (apply-documented-fp-rule config)
      (apply-true-positive-rule config)))

(defn apply-rules-to-all
  "Apply rules to all violations. Returns {:violations [...] :changes [...]}."
  [violations config]
  (let [results (mapv #(apply-rules % config) violations)
        changes (vec (keep-indexed
                      (fn [idx v]
                        (when (:classification/rule-applied v)
                          {:index idx
                           :violation-id (:violation/id v)
                           :rule (:classification/rule-applied v)}))
                      results))]
    {:violations results :changes changes}))

;; -------------------------------------------------------------------------- Layer 3
;; Severity filtering

(def severity-rank
  (config/severity-rank))

(defn filter-unresolved-errors
  "Return violations that are true-positive and at or above severity threshold."
  [violations config]
  (let [max-severity (get config :max-error-severity :error)
        threshold    (get severity-rank max-severity 3)]
    (filterv (fn [v]
               (and (true-positive? v)
                    (>= (get severity-rank (:violation/severity v) 0) threshold)))
             violations)))
