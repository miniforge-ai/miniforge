(ns ai.miniforge.pr-train.schema
  "Malli schemas for PR Train component.
   Defines TrainPR, PRTrain, EvidenceBundle, and related types."
  (:require
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Enums and base types

(def pr-statuses
  "Possible states for a PR in a train."
  [:draft :open :reviewing :changes-requested
   :approved :merging :merged :closed :failed])

(def train-statuses
  "Possible states for a PR train."
  [:drafting :open :reviewing :merging
   :merged :failed :rolled-back :abandoned])

(def ci-statuses
  "CI status values."
  [:pending :running :passed :failed :skipped])

(def rollback-triggers
  "What can trigger a rollback."
  [:ci-failure :gate-failure :manual :timeout])

(def rollback-actions
  "Actions to take on rollback."
  [:revert-all :revert-to-checkpoint :pause])

(def evidence-types
  "Types of evidence artifacts."
  [:terraform-plan :atlantis-log :ci-log
   :gate-results :intent-validation :approval-record])

(def intent-types
  "Types of semantic intent."
  [:create :import :modify :delete :migrate])

;------------------------------------------------------------------------------ Layer 1
;; Registry

(def registry
  "Malli registry for PR train types."
  {;; Identifiers
   :id/uuid uuid?
   :id/string [:string {:min 1}]

   ;; PR identifiers
   :pr/repo [:string {:min 1}]
   :pr/number pos-int?
   :pr/url [:string {:min 1}]
   :pr/branch [:string {:min 1}]
   :pr/title [:string {:min 1}]
   :pr/status (into [:enum] pr-statuses)
   :pr/merge-order pos-int?
   :pr/ci-status (into [:enum] ci-statuses)

   ;; Train identifiers
   :train/id :id/uuid
   :train/name [:string {:min 1}]
   :train/status (into [:enum] train-statuses)
   :train/dag-id :id/uuid

   ;; Evidence identifiers
   :evidence/id :id/uuid
   :evidence/type (into [:enum] evidence-types)

   ;; Common types
   :common/timestamp inst?
   :common/non-neg-int [:int {:min 0}]
   :common/pos-number [:double {:min 0.0}]})

;------------------------------------------------------------------------------ Layer 2
;; Gate result schema

(def GateResult
  "Schema for a gate check result."
  [:map {:registry registry}
   [:gate/id keyword?]
   [:gate/type keyword?]
   [:gate/passed? boolean?]
   [:gate/message {:optional true} :id/string]
   [:gate/details {:optional true} [:map-of keyword? any?]]
   [:gate/timestamp {:optional true} :common/timestamp]])

;------------------------------------------------------------------------------ Layer 3
;; Task intent schema (for semantic validation)

(def Invariant
  "Schema for an intent invariant."
  [:map {:registry registry}
   [:invariant/id keyword?]
   [:invariant/description :id/string]
   [:invariant/check
    [:map
     [:type [:enum :plan-output :diff-analysis :state-comparison]]
     [:condition any?]]]])

(def TaskIntent
  "Schema for semantic intent attached to a PR."
  [:map {:registry registry}
   [:intent/type (into [:enum] intent-types)]
   [:intent/invariants {:optional true} [:vector Invariant]]
   [:intent/forbidden-actions {:optional true} [:vector keyword?]]
   [:intent/required-evidence {:optional true} [:vector keyword?]]])

;------------------------------------------------------------------------------ Layer 4
;; TrainPR schema

(def TrainPR
  "Schema for an individual PR in a train."
  [:map {:registry registry}
   [:pr/repo :pr/repo]
   [:pr/number :pr/number]
   [:pr/url :pr/url]
   [:pr/branch :pr/branch]
   [:pr/title :pr/title]
   [:pr/status :pr/status]
   [:pr/merge-order :pr/merge-order]
   [:pr/depends-on [:vector :pr/number]]
   [:pr/blocks [:vector :pr/number]]
   [:pr/ci-status :pr/ci-status]
   [:pr/gate-results {:optional true} [:vector GateResult]]
   [:pr/intent {:optional true} TaskIntent]

   ;; Tier 2 governance fields (backward-compatible)
   [:pr/readiness-score {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:pr/risk {:optional true}
    [:map
     [:risk/score :common/pos-number]
     [:risk/level [:enum :low :medium :high :critical]]
     [:risk/factors {:optional true} [:vector [:map-of keyword? any?]]]]]
   [:pr/automation-tier {:optional true} [:enum :tier-0 :tier-1 :tier-2 :tier-3]]
   [:pr/derived-state {:optional true}
    [:map
     [:age-days :common/non-neg-int]
     [:staleness-hours :common/non-neg-int]
     [:change-size {:optional true}
      [:map
       [:additions :common/non-neg-int]
       [:deletions :common/non-neg-int]]]]]])

;------------------------------------------------------------------------------ Layer 5
;; Progress schema

(def Progress
  "Schema for train progress summary."
  [:map {:registry registry}
   [:total pos-int?]
   [:merged :common/non-neg-int]
   [:approved :common/non-neg-int]
   [:pending :common/non-neg-int]
   [:failed :common/non-neg-int]])

;------------------------------------------------------------------------------ Layer 6
;; Rollback plan schema

(def RollbackPlan
  "Schema for train rollback configuration."
  [:map {:registry registry}
   [:trigger (into [:enum] rollback-triggers)]
   [:action (into [:enum] rollback-actions)]
   [:checkpoint {:optional true} :pr/number]
   [:prs-to-revert [:vector :pr/number]]])

;------------------------------------------------------------------------------ Layer 7
;; PRTrain schema

(def PRTrain
  "Schema for a complete PR train."
  [:map {:registry registry}
   [:train/id :train/id]
   [:train/name :train/name]
   [:train/description {:optional true} :id/string]
   [:train/dag-id :train/dag-id]
   [:train/status :train/status]
   [:train/prs [:vector TrainPR]]

   ;; Computed aggregate state
   [:train/blocking-prs [:vector :pr/number]]
   [:train/ready-to-merge [:vector :pr/number]]
   [:train/progress {:optional true} Progress]

   ;; Rollback configuration
   [:train/rollback-plan {:optional true} RollbackPlan]

   ;; Evidence reference
   [:train/evidence-bundle-id {:optional true} :id/uuid]

   ;; Timing
   [:train/created-at :common/timestamp]
   [:train/updated-at :common/timestamp]
   [:train/merged-at {:optional true} :common/timestamp]])

;------------------------------------------------------------------------------ Layer 8
;; Evidence artifact schema

(def EvidenceArtifact
  "Schema for an individual evidence artifact."
  [:map {:registry registry}
   [:type :evidence/type]
   [:content :id/string]
   [:hash :id/string]
   [:timestamp :common/timestamp]])

(def PREvidence
  "Schema for evidence associated with a single PR."
  [:map {:registry registry}
   [:pr/repo :pr/repo]
   [:pr/number :pr/number]
   [:evidence/artifacts [:vector EvidenceArtifact]]])

(def EvidenceSummary
  "Schema for evidence bundle summary."
  [:map {:registry registry}
   [:total-prs pos-int?]
   [:gates-passed :common/non-neg-int]
   [:gates-failed :common/non-neg-int]
   [:human-approvals :common/non-neg-int]
   [:semantic-violations :common/non-neg-int]])

(def EvidenceBundle
  "Schema for the complete evidence bundle of a train."
  [:map {:registry registry}
   [:evidence/id :evidence/id]
   [:evidence/train-id :train/id]
   [:evidence/created-at :common/timestamp]
   [:evidence/prs [:vector PREvidence]]
   [:evidence/summary EvidenceSummary]
   [:evidence/miniforge-version :id/string]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate TrainPR
  (m/validate TrainPR
              {:pr/repo "acme/terraform"
               :pr/number 123
               :pr/url "https://github.com/acme/terraform/pull/123"
               :pr/branch "feature/auth"
               :pr/title "Add auth module"
               :pr/status :open
               :pr/merge-order 1
               :pr/depends-on []
               :pr/blocks [124]
               :pr/ci-status :pending})
  ;; => true

  ;; Validate PRTrain
  (m/validate PRTrain
              {:train/id (random-uuid)
               :train/name "Add User Auth"
               :train/dag-id (random-uuid)
               :train/status :drafting
               :train/prs []
               :train/blocking-prs []
               :train/ready-to-merge []
               :train/created-at (java.util.Date.)
               :train/updated-at (java.util.Date.)})
  ;; => true

  ;; Validate EvidenceBundle
  (m/validate EvidenceBundle
              {:evidence/id (random-uuid)
               :evidence/train-id (random-uuid)
               :evidence/created-at (java.util.Date.)
               :evidence/prs []
               :evidence/summary {:total-prs 3
                                  :gates-passed 5
                                  :gates-failed 0
                                  :human-approvals 3
                                  :semantic-violations 0}
               :evidence/miniforge-version "0.1.0"})
  ;; => true

  :end)
