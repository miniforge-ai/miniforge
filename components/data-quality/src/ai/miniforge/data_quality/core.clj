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
   {:record-index :rule/id :error/kind ...}. Empty when every rule ran."
  [evaluation]
  (vec
   (for [{:keys [record-index evaluations]} evaluation
         {:keys [rule/error rule/id]} evaluations
         :when error]
     (merge {:record-index record-index :rule/id id} error))))

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

(defn- ^{:stratum 0} tally-field-violation
  "Accumulate a violation count for a field, skipping nil fields."
  [acc {:keys [field]}]
  (if field
    (update-in acc [field :violations] (fnil inc 0))
    acc))

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
  "Compute per-field violation counts and pass rates."
  [violations total]
  (let [counts (reduce tally-field-violation {} violations)]
    (reduce-kv (fn [acc field {:keys [violations]}]
                 (assoc acc field
                        {:violations violations
                         :pass-rate  (if (zero? total)
                                       1.0
                                       (double (/ (- total violations) total)))}))
               {}
               counts)))

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
        summary    (compute-field-summary violations total)
        report     (result/quality-report pack-id total passed failed violations summary)]
    (assoc report :report/rule-errors errors)))
