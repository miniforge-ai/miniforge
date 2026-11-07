(ns ai.miniforge.data-quality.interface
  "Public API for the data-quality component."
  (:require [ai.miniforge.data-quality.core :as core]
            [ai.miniforge.data-quality.rules :as rules]))

;------------------------------------------------------------------------------ Layer 0

;; -- Rule Constructors --
(defn ^{:stratum 0} required-rule
  "Create a rule checking field presence and non-nil value.
   Options: :severity (:error | :warning), default :error"
  [field & opts]
  (apply rules/required-rule field opts))

(defn ^{:stratum 0} type-check-rule
  "Create a rule checking field value type against dataset-schema field types.
   Options: :severity (:error | :warning), default :error"
  [field expected-type & opts]
  (apply rules/type-check-rule field expected-type opts))

(defn ^{:stratum 0} range-rule
  "Create a rule checking numeric bounds: min <= value <= max.
   Options: :severity (:error | :warning), default :error"
  [field min-val max-val & opts]
  (apply rules/range-rule field min-val max-val opts))

(defn ^{:stratum 0} pattern-rule
  "Create a rule checking string field against regex pattern.
   Options: :severity (:error | :warning), default :warning"
  [field pattern & opts]
  (apply rules/pattern-rule field pattern opts))

(defn ^{:stratum 0} custom-rule
  "Create a rule with arbitrary check function.
   check-fn: (fn [record] {:valid? bool :message str})
   Options: :severity (:error | :warning), default :error"
  [rule-id check-fn & opts]
  (apply rules/custom-rule rule-id check-fn opts))

;; -- Evaluation --
(defn ^{:stratum 0} evaluate-records
  "Evaluate all records against all rules.
   Returns vector of {:record-index :record :evaluations [...]}."
  [rules records]
  (core/evaluate-records rules records))

(defn ^{:stratum 0} rule-errors
  "Every rule evaluation in an evaluation that failed to execute: its check fn
   threw, or returned something other than a map with a boolean :valid?.
   Returns [{:record-index :rule/id :error/kind ...}]; empty when every rule
   ran. Such a failure is an execution error, not a domain violation."
  [evaluation]
  (core/rule-errors evaluation))

(defn ^{:stratum 0} filter-passed
  "Return records passing all :error-severity rules. Warnings pass through."
  [evaluation]
  (core/filter-passed evaluation))

(defn ^{:stratum 0} generate-report
  "Build quality report with field-level summary statistics.
   Returns {:report/pack-id :report/total :report/passed :report/failed
            :report/violations [...] :report/field-summary {...}}"
  [pack-id evaluation]
  (core/generate-report pack-id evaluation))
