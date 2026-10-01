;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.dag-records
  "N6 section 2.7 task lifecycle and measurement records.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Cost [:and number? [:>= 0]])

(def ^{:stratum 0} Check
  [:map [:name string?] [:status keyword?] [:duration-ms nat-int?]])

(def ^{:stratum 0} PullRequest
  [:map
   [:pr/id string?] [:pr/url string?] [:pr/branch string?]
   [:pr/base-sha string?] [:pr/head-sha string?] [:pr/opened-at inst?]
   [:pr/merged-at {:optional true} inst?]
   [:pr/closed-at {:optional true} inst?]])

(def ^{:stratum 0} Review
  [:map
   [:review/sha string?]
   [:review/status [:enum :approved :changes-requested]]
   [:review/approvers [:vector string?]]
   [:review/changes-requested-by [:vector string?]]
   [:review/reviewed-at inst?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Metrics
  [:map [:tokens nat-int?] [:cost-usd Cost] [:duration-ms nat-int?]])

(def ^{:stratum 1} RunMetrics
  [:map [:total-tokens nat-int?] [:total-cost-usd Cost] [:total-duration-ms nat-int?]])

(def ^{:stratum 1} ContinuousIntegration
  [:map
   [:ci/sha string?] [:ci/status [:enum :success :failure]]
   [:ci/checks [:vector Check]] [:ci/checked-at inst?]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} TaskMetrics
  (into Metrics [[:total-attempts nat-int?] [:fix-iterations nat-int?] [:ci-retries nat-int?]]))

(def ^{:stratum 2} Fix
  [:map
   [:fix/iteration pos-int?] [:fix/type [:enum :ci-failure :review-changes :conflict]]
   [:fix/trigger-sha string?] [:fix/result-sha string?]
   [:fix/files-modified [:vector string?]] [:fix/success? boolean?]
   [:fix/metrics Metrics] [:fix/attempted-at inst?]])

(comment
  TaskMetrics)
