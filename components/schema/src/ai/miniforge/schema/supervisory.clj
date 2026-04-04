(ns ai.miniforge.schema.supervisory
  "Open Malli schemas for v1 supervisory entities.

   Defines schemas for the six core supervisory domain types:
   WorkflowRun, PolicyEvaluation, PolicyViolation, AttentionItem,
   Waiver, and EvidenceBundle.

   All schemas are *open* maps — required keys are validated but
   extra keys pass through without error. This allows upstream
   producers to annotate entities with ad-hoc metadata without
   breaking validation.

   Layer 0: Enums and registry extensions
   Layer 1: Composite schemas"
  (:require
   [ai.miniforge.schema.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Enums for supervisory entities

(def eval-results
  "Possible outcomes of a policy evaluation."
  [:pass :fail :warn :skip])

(def violation-categories
  "Categories of policy violations."
  [:style :security :testing :documentation :architecture :process :budget])

(def violation-severities
  "Severity levels for violations and attention items."
  [:info :low :medium :high :critical])

(def attention-source-types
  "Source types that can generate attention items."
  [:workflow :policy :agent :system :human])

;------------------------------------------------------------------------------ Layer 0a
;; Registry extensions for supervisory types

(def supervisory-registry
  "Malli registry extending core registry with supervisory types."
  (merge
   core/registry
   {;; Evaluation types
    :eval/id            :id/uuid
    :eval/result        (into [:enum] eval-results)

    ;; Violation types
    :violation/id       :id/uuid
    :violation/category (into [:enum] violation-categories)
    :violation/severity (into [:enum] violation-severities)

    ;; Attention types
    :attention/id       :id/uuid
    :attention/severity (into [:enum] violation-severities)
    :attention/source-type (into [:enum] attention-source-types)

    ;; Waiver types
    :waiver/id         :id/uuid

    ;; Evidence types
    :evidence/id       :id/uuid}))

;------------------------------------------------------------------------------ Layer 1
;; Composite schemas — all open maps (no {:closed true})

(def WorkflowRun
  "Schema for a supervisory workflow run.
   Tracks the lifecycle of a single workflow execution.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:workflow/id :workflow/id]
   [:workflow/key [:string {:min 1}]]
   [:workflow/status :workflow/status]
   [:workflow/phase :workflow/phase]
   [:workflow/started-at :common/timestamp]])

(def PolicyEvaluation
  "Schema for a policy evaluation result.
   Records the outcome of evaluating policies against a PR.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:eval/id :eval/id]
   [:eval/pr-id [:string {:min 1}]]
   [:eval/result :eval/result]
   [:eval/rules-applied [:vector keyword?]]
   [:eval/evaluated-at :common/timestamp]])

(def PolicyViolation
  "Schema for a policy violation.
   Identifies a specific rule violation within an evaluation.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:violation/id :violation/id]
   [:violation/eval-id :eval/id]
   [:violation/rule [:string {:min 1}]]
   [:violation/category :violation/category]
   [:violation/severity :violation/severity]])

(def AttentionItem
  "Schema for an attention item requiring human review.
   Surfaces items needing oversight from any source in the system.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:attention/id :attention/id]
   [:attention/severity :attention/severity]
   [:attention/summary [:string {:min 1}]]
   [:attention/source-type :attention/source-type]
   [:attention/source-id [:string {:min 1}]]])

(def Waiver
  "Schema for a policy waiver.
   Records a human decision to waive a policy violation.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:waiver/id :waiver/id]
   [:waiver/eval-id :eval/id]
   [:waiver/actor [:string {:min 1}]]
   [:waiver/reason [:string {:min 1}]]
   [:waiver/created-at :common/timestamp]])

(def EvidenceBundle
  "Schema for an evidence bundle.
   Collects evidence entries produced during a workflow.
   Open map — extra keys pass through."
  [:map {:registry supervisory-registry}
   [:evidence/id :evidence/id]
   [:evidence/workflow-id :workflow/id]
   [:evidence/entries [:vector [:map-of keyword? any?]]]])

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[malli.core :as m])

  ;; Validate WorkflowRun
  (m/validate WorkflowRun
              {:workflow/id (random-uuid)
               :workflow/key "feature-auth"
               :workflow/status :running
               :workflow/phase :implement
               :workflow/started-at (java.util.Date.)})
  ;; => true

  ;; Extra keys pass through
  (m/validate WorkflowRun
              {:workflow/id (random-uuid)
               :workflow/key "feature-auth"
               :workflow/status :running
               :workflow/phase :implement
               :workflow/started-at (java.util.Date.)
               :custom/extra "allowed"
               :another-key 42})
  ;; => true

  ;; Missing required key fails
  (m/validate WorkflowRun
              {:workflow/id (random-uuid)
               :workflow/status :running})
  ;; => false

  ;; Validate PolicyEvaluation
  (m/validate PolicyEvaluation
              {:eval/id (random-uuid)
               :eval/pr-id "repo/123"
               :eval/result :pass
               :eval/rules-applied [:no-force-push :require-tests]
               :eval/evaluated-at (java.util.Date.)})
  ;; => true

  ;; Validate EvidenceBundle
  (m/validate EvidenceBundle
              {:evidence/id (random-uuid)
               :evidence/workflow-id (random-uuid)
               :evidence/entries [{:type :test-result :passed? true}
                                  {:type :coverage :value 0.85}]})
  ;; => true

  :leave-this-here)