(ns ai.miniforge.data-quality.result
  "Factory functions for data-quality result maps.")

(defn evaluation-entry
  "Build one entry in an evaluation vector."
  [record-index record evaluations]
  {:record-index record-index
   :record       record
   :evaluations  evaluations})

(defn violation
  "Build a single violation record."
  [record-index rule-id field severity message]
  {:record-index record-index
   :rule-id      rule-id
   :field        (when field (name field))
   :severity     severity
   :message      message})

(defn quality-report
  "Build a quality report map."
  [pack-id total passed failed violations field-summary]
  {:report/pack-id       pack-id
   :report/total         total
   :report/passed        passed
   :report/failed        failed
   :report/violations    violations
   :report/field-summary field-summary})
