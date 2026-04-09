(ns ai.miniforge.data-foundry.data-quality.interface
  "Public API for the data-quality component."
  (:require [ai.miniforge.data-foundry.data-quality.core :as core]
            [ai.miniforge.data-foundry.data-quality.rules :as rules]))

;; -- Rule Constructors --

(defn required-rule
  "Create a rule checking field presence and non-nil value.
   Options: :severity (:error | :warning), default :error"
  [field & opts]
  (apply rules/required-rule field opts))

(defn type-check-rule
  "Create a rule checking field value type against dataset-schema field types.
   Options: :severity (:error | :warning), default :error"
  [field expected-type & opts]
  (apply rules/type-check-rule field expected-type opts))

(defn range-rule
  "Create a rule checking numeric bounds: min <= value <= max.
   Options: :severity (:error | :warning), default :error"
  [field min-val max-val & opts]
  (apply rules/range-rule field min-val max-val opts))

(defn pattern-rule
  "Create a rule checking string field against regex pattern.
   Options: :severity (:error | :warning), default :warning"
  [field pattern & opts]
  (apply rules/pattern-rule field pattern opts))

(defn custom-rule
  "Create a rule with arbitrary check function.
   check-fn: (fn [record] {:valid? bool :message str})
   Options: :severity (:error | :warning), default :error"
  [rule-id check-fn & opts]
  (apply rules/custom-rule rule-id check-fn opts))

;; -- Evaluation --

(defn evaluate-records
  "Evaluate all records against all rules.
   Returns vector of {:record-index :record :evaluations [...]}."
  [rules records]
  (core/evaluate-records rules records))

(defn filter-passed
  "Return records passing all :error-severity rules. Warnings pass through."
  [evaluation]
  (core/filter-passed evaluation))

(defn generate-report
  "Build quality report with field-level summary statistics.
   Returns {:report/pack-id :report/total :report/passed :report/failed
            :report/violations [...] :report/field-summary {...}}"
  [pack-id evaluation]
  (core/generate-report pack-id evaluation))
