(ns ai.miniforge.data-quality.core
  "Evaluation engine and report generation for data quality."
  (:require [ai.miniforge.data-quality.invoke :as invoke]
            [ai.miniforge.data-quality.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} evaluate-record
  "Evaluate a single record against all rules.
   Returns vector of {:rule/id :field :severity :valid? :message}; an entry
   whose rule failed to execute also carries :rule/error."
  [rules record]
  (mapv #(invoke/evaluate-rule % record) rules))

(defn ^{:stratum 0} rule-errors
  "Every rule evaluation in `evaluation` that failed to execute, as
   {:record-index :rule/id :field :error/kind ...}. :field is the rule's field
   name as a string, as in violations. Empty when every rule ran."
  [evaluation]
  (vec
   (for [{:keys [record-index evaluations]} evaluation
         {:keys [rule/error rule/id field]} evaluations
         :when error]
     (merge {:record-index record-index :rule/id id :field (when field (name field))} error))))

(defn- ^{:stratum 0} blocking-evaluation?
  "An evaluation that keeps its record out: a rule that failed to execute,
   whatever its severity, or an error-severity rule the record failed."
  [evaluation]
  (or (some? (:rule/error evaluation))
      (and (not (:valid? evaluation))
           (= :error (:severity evaluation)))))

;; -- Report helpers (decomposed from generate-report) --
(defn- ^{:stratum 0} collect-violations
  "Extract violation records from a full evaluation: rules that ran and
   rejected the record. A rule that failed to execute is not a violation; it
   is reported under :report/rule-errors."
  [evaluation]
  (vec
   (for [{:keys [record-index evaluations]} evaluation
         {:keys [valid? rule/id rule/error field severity message]} evaluations
         :when (and (not valid?) (nil? error))]
     (result/violation record-index id field severity message))))

(defn- ^{:stratum 0} tally-field
  "Accumulate a count under `count-key` for an entry's field, skipping nil
   fields."
  [count-key acc {:keys [field]}]
  (if field
    (update-in acc [field count-key] (fnil inc 0))
    acc))

(defn- ^{:stratum 0} field-summary-entry
  "One field's counts and pass rate: the share of records whose rules on the
   field ran and passed. A rule that failed to execute counts against the
   field, like a violation."
  [total {:keys [violations rule-errors] :or {violations 0 rule-errors 0}}]
  (let [pass-rate (if (zero? total)
                    1.0
                    (double (/ (- total violations rule-errors) total)))]
    {:violations  violations
     :rule-errors rule-errors
     :pass-rate   pass-rate}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} pass-record
  "Return the record when none of its evaluations blocks it, else nil."
  [{:keys [record evaluations]}]
  (when-not (some blocking-evaluation? evaluations) record))

(defn ^{:stratum 1} evaluate-records
  "Evaluate all records against all rules.
   Returns vector of evaluation-entry maps."
  [rules records]
  (mapv (fn [idx record]
          (result/evaluation-entry idx record (evaluate-record rules record)))
        (range)
        records))

(defn- ^{:stratum 1} compute-field-summary
  "Compute per-field violation and rule-error counts and pass rates. A field
   whose rule failed to execute is not reported as passing."
  [violations errors total]
  (let [counts (as-> {} counts
                 (reduce (partial tally-field :violations) counts violations)
                 (reduce (partial tally-field :rule-errors) counts errors))]
    (update-vals counts #(field-summary-entry total %))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} filter-passed
  "Return records that pass all :error-severity rules and whose rules all ran.
   Warning violations do not cause filtering; a warning rule that failed to
   execute does."
  [evaluation]
  (vec (keep pass-record evaluation)))

(defn ^{:stratum 2} generate-report
  "Build a quality report with field-level summary statistics. Rules that
   failed to execute are listed under :report/rule-errors, apart from
   violations; their records count as failed, never as passed."
  [pack-id evaluation]
  (let [total      (count evaluation)
        violations (collect-violations evaluation)
        errors     (rule-errors evaluation)
        failed     (count (distinct (map :record-index (concat violations errors))))
        passed     (- total failed)
        summary    (compute-field-summary violations errors total)
        report     (result/quality-report pack-id total passed failed violations summary)]
    (assoc report :report/rule-errors errors)))
