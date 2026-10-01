;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.dag-evidence
  "Declared N6 DAG, task and merge evidence; no external-state verification."
  (:require [ai.miniforge.evidence-bundle.schema.dag-records :as records]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Run
  [:map
   [:dag/id uuid?] [:run/id uuid?] [:run/status [:enum :completed :failed :partial]]
   [:run/task-count nat-int?] [:run/merged-count nat-int?]
   [:run/failed-count nat-int?] [:run/skipped-count nat-int?]
   [:run/metrics records/RunMetrics]
   [:run/checkpoint {:optional true} [:map [:ref string?]]]])

(def ^{:stratum 0} Task
  [:map
   [:task/id uuid?] [:task/status [:enum :merged :failed :skipped]]
   [:task/dependencies [:vector uuid?]]
   [:task/pr-lifecycle records/PullRequest]
   [:task/ci-results [:vector records/ContinuousIntegration]]
   [:task/review-results [:vector records/Review]]
   [:task/fix-iterations [:vector records/Fix]]
   [:task/metrics records/TaskMetrics]])

(def ^{:stratum 0} Merge
  [:map
   [:merge/pr-id string?] [:merge/sha string?] [:merge/method [:enum :merge :squash :rebase]]
   [:merge/merged-by string?] [:merge/merged-at inst?]
   [:merge/required-approvals-met? boolean?] [:merge/ci-green? boolean?]
   [:merge/no-unresolved-threads? boolean?] [:merge/branch-up-to-date? boolean?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} run? (m/validator Run))

(def ^{:stratum 1} tasks? (m/validator [:vector Task]))

(def ^{:stratum 1} merge? (m/validator Merge))

(comment
  (run? {}))
