;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.dag-fixtures)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} at #inst "2026-10-01")

(def ^{:stratum 0} metrics {:tokens 0 :cost-usd 0M :duration-ms 0})

(defn ^{:stratum 0} run []
  {:dag/id (random-uuid) :run/id (random-uuid) :run/status :completed
   :run/task-count 1 :run/merged-count 1 :run/failed-count 0 :run/skipped-count 0
   :run/metrics {:total-tokens 0 :total-cost-usd 0M :total-duration-ms 0}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pull-request []
  {:pr/id "1" :pr/url "https://example.test/pull/1" :pr/branch "task"
   :pr/base-sha "base" :pr/head-sha "head" :pr/opened-at at})

(defn ^{:stratum 1} ci []
  {:ci/sha "head" :ci/status :success :ci/checked-at at
   :ci/checks [{:name "test" :status :success :duration-ms 0}]})

(defn ^{:stratum 1} review []
  {:review/sha "head" :review/status :approved :review/approvers ["reviewer"]
   :review/changes-requested-by [] :review/reviewed-at at})

(defn ^{:stratum 1} fix []
  {:fix/iteration 1 :fix/type :ci-failure :fix/trigger-sha "base" :fix/result-sha "head"
   :fix/files-modified ["example.clj"] :fix/success? true :fix/metrics metrics :fix/attempted-at at})

(defn ^{:stratum 1} merge-evidence []
  {:merge/pr-id "1" :merge/sha "merged" :merge/method :squash
   :merge/merged-by "operator" :merge/merged-at at
   :merge/required-approvals-met? true :merge/ci-green? true
   :merge/no-unresolved-threads? true :merge/branch-up-to-date? true})

(defn ^{:stratum 1} task-metrics []
  (assoc metrics :total-attempts 2 :fix-iterations 1 :ci-retries 0))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} task []
  {:task/id (random-uuid) :task/status :merged :task/dependencies []
   :task/pr-lifecycle (pull-request) :task/ci-results [(ci)]
   :task/review-results [(review)] :task/fix-iterations [(fix)] :task/metrics (task-metrics)})

(comment
  (task))
