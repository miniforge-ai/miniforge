(ns ai.miniforge.pr-train.interface
  "Public API for the pr-train component.
   Manages linked PR trains with coordinated state and merge ordering."
  (:require
   [ai.miniforge.pr-train.core :as core]
   [ai.miniforge.pr-train.schema :as schema]
   [ai.miniforge.pr-train.state :as state]
   [ai.miniforge.pr-train.readiness :as readiness]
   [ai.miniforge.pr-train.risk :as risk]
   [ai.miniforge.pr-train.tiers :as tiers]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol and schema re-exports

(def PRTrainManager core/PRTrainManager)

;; Schema re-exports
(def TrainPR schema/TrainPR)
(def PRTrain schema/PRTrain)
(def EvidenceBundle schema/EvidenceBundle)
(def GateResult schema/GateResult)
(def TaskIntent schema/TaskIntent)
(def Progress schema/Progress)
(def RollbackPlan schema/RollbackPlan)

;; Enum value sets
(def pr-statuses schema/pr-statuses)
(def train-statuses schema/train-statuses)
(def ci-statuses schema/ci-statuses)
(def rollback-triggers schema/rollback-triggers)
(def evidence-types schema/evidence-types)

;; State machine re-exports
(def train-transitions state/train-transitions)
(def pr-transitions state/pr-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Manager creation

(def create-manager
  "Create a new PR train manager.

   Returns an InMemoryPRTrainManager instance."
  core/create-manager)

;------------------------------------------------------------------------------ Layer 2
;; Train lifecycle

(defn create-train
  "Create a new PR train linked to a repo DAG.

   Returns train-id (UUID)."
  [manager name dag-id description]
  (core/create-train manager name dag-id description))

(defn add-pr
  "Add a PR to the train.

   Returns updated train or nil if train not found."
  [manager train-id repo pr-number url branch title]
  (core/add-pr manager train-id repo pr-number url branch title))

(defn remove-pr
  "Remove a PR from the train.

   Returns updated train or nil if train/PR not found."
  [manager train-id pr-number]
  (core/remove-pr manager train-id pr-number))

(defn link-prs
  "Auto-compute depends-on/blocks relationships.

   Returns updated train."
  [manager train-id]
  (core/link-prs manager train-id))

;------------------------------------------------------------------------------ Layer 3
;; State management

(defn sync-pr-status
  "Update status for all PRs from external source.

   Returns updated train."
  [manager train-id status-map]
  (core/sync-pr-status manager train-id status-map))

(defn update-pr-status
  "Update status for a single PR.

   Returns updated train or nil if invalid transition."
  [manager train-id pr-number new-status]
  (core/update-pr-status manager train-id pr-number new-status))

(defn update-pr-ci-status
  "Update CI status for a single PR.

   Returns updated train."
  [manager train-id pr-number ci-status]
  (core/update-pr-ci-status manager train-id pr-number ci-status))

;------------------------------------------------------------------------------ Layer 4
;; Queries

(defn get-train
  "Retrieve a train by ID.

   Returns full PRTrain map or nil if not found."
  [manager train-id]
  (core/get-train manager train-id))

(defn get-blocking
  "Return PRs that are blocking train progress.

   Returns vector of PR numbers sorted by merge order."
  [manager train-id]
  (core/get-blocking manager train-id))

(defn get-ready-to-merge
  "Return PRs that can merge now.

   Returns vector of PR numbers sorted by merge order."
  [manager train-id]
  (core/get-ready-to-merge manager train-id))

(defn get-progress
  "Return progress summary.

   Returns {:total :merged :approved :pending :failed}."
  [manager train-id]
  (core/get-progress manager train-id))

;------------------------------------------------------------------------------ Layer 5
;; Merge actions

(defn merge-next
  "Mark the next ready PR as merging.

   Returns {:pr-number :train} or nil if none ready."
  [manager train-id]
  (core/merge-next manager train-id))

(defn complete-merge
  "Mark a PR merge as complete.

   Returns updated train."
  [manager train-id pr-number]
  (core/complete-merge manager train-id pr-number))

(defn fail-merge
  "Mark a PR merge as failed.

   Returns updated train."
  [manager train-id pr-number reason]
  (core/fail-merge manager train-id pr-number reason))

(defn merge-all-ready
  "Get all PRs ready to merge in order.

   Returns vector of PR numbers."
  [manager train-id]
  (get-ready-to-merge manager train-id))

;------------------------------------------------------------------------------ Layer 6
;; Train control

(defn pause-train
  "Pause all train activity.

   Returns updated train."
  [manager train-id reason]
  (core/pause-train manager train-id reason))

(defn resume-train
  "Resume paused train.

   Returns updated train."
  [manager train-id]
  (core/resume-train manager train-id))

(defn rollback
  "Plan rollback: compute which PRs to revert.

   Returns updated train with :train/rollback-plan set."
  [manager train-id trigger reason]
  (core/rollback manager train-id trigger reason))

(defn abandon-train
  "Mark train as abandoned.

   Returns updated train."
  [manager train-id reason]
  (core/abandon-train manager train-id reason))

;------------------------------------------------------------------------------ Layer 7
;; Evidence

(defn generate-evidence-bundle
  "Collect and bundle all evidence artifacts.

   Returns EvidenceBundle."
  [manager train-id]
  (core/generate-evidence-bundle manager train-id))

(defn get-evidence-bundle
  "Retrieve evidence bundle by ID.

   Returns EvidenceBundle or nil if not found."
  [manager bundle-id]
  (core/get-evidence-bundle manager bundle-id))

;------------------------------------------------------------------------------ Layer 8
;; Utility functions

(def list-trains
  "List all trains in a manager."
  core/list-trains)

(def find-trains-by-status
  "Find trains with a given status."
  core/find-trains-by-status)

(def find-trains-by-dag
  "Find trains using a specific DAG."
  core/find-trains-by-dag)

(def train-contains-pr?
  "Check if a train contains a specific PR."
  core/train-contains-pr?)

(def get-pr-from-train
  "Get a PR from a train."
  core/get-pr-from-train)

;------------------------------------------------------------------------------ Layer 9
;; State machine queries

(defn valid-train-transition?
  "Check if a train status transition is valid."
  [from-status to-status]
  (state/valid-train-transition? from-status to-status))

(defn valid-pr-transition?
  "Check if a PR status transition is valid."
  [from-status to-status]
  (state/valid-pr-transition? from-status to-status))

(defn ready-to-merge?
  "Check if a PR is ready to merge within a train context."
  [train pr]
  (state/ready-to-merge? train pr))

;------------------------------------------------------------------------------ Layer 10
;; Readiness scoring (N9)

(def compute-readiness-score
  "Compute weighted readiness score for a PR in a train. Returns double in [0.0, 1.0]."
  readiness/compute-readiness-score)

(def explain-readiness
  "Explain readiness score breakdown. Returns map with :readiness/score, :readiness/factors."
  readiness/explain-readiness)

;------------------------------------------------------------------------------ Layer 11
;; Risk assessment (N9)

(def assess-risk
  "Assess overall risk for a PR. Returns {:risk/score :risk/level :risk/factors}."
  risk/assess-risk)

;------------------------------------------------------------------------------ Layer 12
;; Automation tiers (N9)

(def get-automation-tier
  "Get effective automation tier for a repository."
  tiers/get-automation-tier)

(def tier-allows?
  "Check if a tier allows a specific operation given readiness and risk."
  tiers/tier-allows?)

(def can-auto-approve?
  "Check if a PR can be auto-approved."
  tiers/can-auto-approve?)

(def can-auto-merge?
  "Check if a PR can be auto-merged."
  tiers/can-auto-merge?)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def mgr (create-manager))
  (def dag-id (random-uuid))
  (def train-id (create-train mgr "Add Authentication" dag-id "Auth feature"))
  (add-pr mgr train-id "acme/terraform-modules" 100
          "https://github.com/acme/terraform-modules/pull/100"
          "feat/auth" "Add Cognito module")
  (add-pr mgr train-id "acme/terraform-live" 200
          "https://github.com/acme/terraform-live/pull/200"
          "feat/auth" "Deploy Cognito")
  (link-prs mgr train-id)
  (update-pr-status mgr train-id 100 :open)
  (update-pr-status mgr train-id 100 :reviewing)
  (update-pr-status mgr train-id 100 :approved)
  (update-pr-ci-status mgr train-id 100 :passed)
  (get-ready-to-merge mgr train-id)
  (merge-next mgr train-id)
  (complete-merge mgr train-id 100)
  (get-progress mgr train-id)
  (generate-evidence-bundle mgr train-id)
  :end)
