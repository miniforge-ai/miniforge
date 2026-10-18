;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.extended-schema-test
  (:require [ai.miniforge.evidence-bundle.dag-fixtures :as dag]
            [ai.miniforge.evidence-bundle.extended-fixtures :as extended]
            [ai.miniforge.evidence-bundle.opsv-test-fixtures :as base]
            [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} sections []
  {:evidence/dag-run (dag/run)
   :evidence/task-workflows [(dag/task)]
   :evidence/merge (dag/merge-evidence)
   :evidence/annotations [(extended/annotation)]
   :evidence/pack-run (extended/pack-run)})

(defn- ^{:stratum 0} valid? [sections]
  (let [bundle (assoc base/base-bundle :evidence-bundle/id base/canonical-bundle-id)
        value (merge bundle sections)]
    (:valid? (validation/validate-schema schema/evidence-bundle-schema value))))

(def ^{:stratum 0} malformed-fields
  [[[:evidence/dag-run :run/status] :unknown]
   [[:evidence/dag-run :run/task-count] -1]
   [[:evidence/dag-run :run/metrics :total-cost-usd] "0"]
   [[:evidence/dag-run :run/checkpoint] {}]
   [[:evidence/task-workflows 0 :task/status] :running]
   [[:evidence/task-workflows 0 :task/dependencies] ["not-a-uuid"]]
   [[:evidence/task-workflows 0 :task/pr-lifecycle :pr/merged-at] nil]
   [[:evidence/task-workflows 0 :task/ci-results 0 :ci/status] :pending]
   [[:evidence/task-workflows 0 :task/ci-results 0 :ci/checks 0 :duration-ms] -1]
   [[:evidence/task-workflows 0 :task/review-results 0 :review/approvers] [42]]
   [[:evidence/task-workflows 0 :task/fix-iterations 0 :fix/iteration] 0]
   [[:evidence/task-workflows 0 :task/fix-iterations 0 :fix/metrics :tokens] -1]
   [[:evidence/task-workflows 0 :task/metrics :total-attempts] -1]
   [[:evidence/merge :merge/method] :unknown]
   [[:evidence/merge :merge/ci-green?] "true"]
   [[:evidence/annotations 0 :annotation/type] :unknown]
   [[:evidence/annotations 0 :annotation/source :listener-id] "listener"]
   [[:evidence/annotations 0 :annotation/target :event-id] nil]
   [[:evidence/annotations 0 :annotation/content :body] 42]
   [[:evidence/pack-run :pack/signature-verified?] "true"]
   [[:evidence/pack-run :pack/signature-error] nil]
   [[:evidence/pack-run :pack/capabilities-required 0 :capability/id] :read]
   [[:evidence/pack-run :pack/capabilities-granted 0 :capability/granted-by] "anyone"]
   [[:evidence/pack-run :pack/capabilities-denied 0 :capability/denied-reason] nil]
   [[:evidence/pack-run :pack/resolved-dependencies 0 :pack/version] 1]
   [[:evidence/pack-run :pack-run/inputs] []]
   [[:evidence/pack-run :pack-run/connector-actions 0 :action/result] :unknown]])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} optional-sections-validate-only-when-present
  (let [value (sections)]
    (is (valid? {}))
    (is (valid? value))
    (doseq [field (keys value)]
      (is (valid? (dissoc value field)))
      (doseq [invalid [nil 42 "record" {} [42]]]
        (is (not (valid? (assoc value field invalid))) (str field " " invalid))))))

(deftest ^{:stratum 1} nested-records-reject-invalid-fields
  (let [value (sections)]
    (doseq [[path invalid] malformed-fields]
      (is (not (valid? (assoc-in value path invalid))) (str path)))
    (doseq [path [[:evidence/task-workflows 0 :task/pr-lifecycle]
                 [:evidence/task-workflows 0 :task/ci-results 0]
                 [:evidence/task-workflows 0 :task/review-results 0]
                 [:evidence/task-workflows 0 :task/fix-iterations 0]
                 [:evidence/task-workflows 0 :task/metrics]
                 [:evidence/annotations 0 :annotation/content]
                 [:evidence/pack-run :pack/capabilities-granted 0]]]
      (doseq [field (keys (get-in value path))]
        (is (not (valid? (update-in value path dissoc field))) (str path " " field))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.evidence-bundle.extended-schema-test))
