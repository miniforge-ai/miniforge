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

(defn- ^{:stratum 0} pass-record
  "Return the record when all its evaluations pass error-severity rules, else nil."
  [{:keys [record evaluations]}]
  (let [has-error (some (fn [e]
                          (and (not (:valid? e))
                               (= :error (:severity e))))
                        evaluations)]
    (when-not has-error record)))

;; -- Report helpers (decomposed from generate-report) --
(defn- ^{:stratum 0} collect-violations
  "Extract violation records from a full evaluation."
  [evaluation]
  (vec
   (for [{:keys [record-index evaluations]} evaluation
         {:keys [valid? rule/id field severity message]} evaluations
         :when (not valid?)]
     (result/violation record-index id field severity message))))

(defn- ^{:stratum 0} tally-field-violation
  "Accumulate a violation count for a field, skipping nil fields."
  [acc {:keys [field]}]
  (if field
    (update-in acc [field :violations] (fnil inc 0))
    acc))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evaluate-records
  "Evaluate all records against all rules.
   Returns vector of evaluation-entry maps."
  [rules records]
  (mapv (fn [idx record]
          (result/evaluation-entry idx record (evaluate-record rules record)))
        (range)
        records))

(defn ^{:stratum 1} filter-passed
  "Return records that pass all :error-severity rules.
   Warnings do not cause filtering."
  [evaluation]
  (vec (keep pass-record evaluation)))

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

(defn ^{:stratum 2} generate-report
  "Build a quality report with field-level summary statistics."
  [pack-id evaluation]
  (let [total      (count evaluation)
        violations (collect-violations evaluation)
        failed     (count (distinct (map :record-index violations)))
        passed     (- total failed)]
    (result/quality-report pack-id total passed failed
                           violations
                           (compute-field-summary violations total))))
