;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-draft-spec
  "Canonical current-write lifecycle contracts from N3 §§3.1 and 3.21."
  (:require [ai.miniforge.event-stream.schema :as legacy]
            [ai.miniforge.failure-classifier.interface :as failure]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Duration [:and int? [:>= 0]])

(def ^{:stratum 0} Identity
  [:map
   [:event/version [:= "1.0.0"]]
   [:scope/type [:= :workflow]]
   [:workflow/id :uuid]])

(def ^{:stratum 0} PhaseAct
  [:multi {:dispatch :phase/outcome}
   [:success :map]
   [:failure :map]
   [:skipped :map]
   [:blocked [:map [:phase/blocked-reason legacy/RefusalReason]]]
   [:redirected [:map [:phase/transition-request legacy/TransitionRequest]]]])

(def ^{:stratum 0} Cancellation
  [:multi {:dispatch :cancel/source}
   [:cli :map]
   [:api :map]
   [:tui :map]
   [:supervisor :map]
   [:control-action [:map [:action/id :uuid]]]])

(def ^{:stratum 0} Terminal
  ;; Outcome is the event type, never a contradictory legacy status discriminator.
  [:map [:workflow/status {:optional true} :nil]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Lifecycle
  [:multi {:dispatch :event/type}
   [:workflow/started
    [:map
     [:workflow/spec :map]
     [:workflow/intent :map]
     [:routing/trigger-event-id {:optional true} [:maybe :uuid]]]]
   [:workflow/phase-started
    [:map
     [:workflow/phase :keyword]
     ;; N3 reserves :agent/none for intentional agentless execution, never unknown identity.
     [:phase/expected-agent :keyword]
     [:phase/context :map]]]
   [:workflow/phase-completed
    [:and PhaseAct
     [:map
      [:workflow/phase :keyword]
      [:phase/duration-ms Duration]
      [:phase/artifacts [:vector :uuid]]
      [:phase/blocked-reason {:optional true} [:maybe legacy/RefusalReason]]
      [:phase/transition-request {:optional true} [:maybe legacy/TransitionRequest]]]]]
   [:workflow/completed
    [:and Terminal
     [:map
      [:workflow/duration-ms Duration]
      [:workflow/evidence-bundle-id :uuid]]]]
   [:workflow/failed
    [:and Terminal
     [:map
      [:workflow/failure-phase {:optional true} [:maybe :keyword]]
      [:workflow/failure-reason :string]
      [:failure/class failure/FailureClass]
      [:workflow/error-details {:optional true} [:maybe :map]]]]]
   [:workflow/cancelled
    [:and Terminal Cancellation
     [:map
      [:cancel/requested-by :string]
      [:cancel/reason {:optional true} [:maybe :string]]
      [:action/id {:optional true} [:maybe :uuid]]
      [:workflow/duration-ms Duration]]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Draft
  "Compose with the common sequence-free envelope at the publication boundary."
  [:and Identity Lifecycle])

(comment
  Draft)
