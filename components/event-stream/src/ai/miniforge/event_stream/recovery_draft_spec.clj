;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-draft-spec
  "N3 §3.21 checkpoint, snapshot and resume admission contracts."
  (:require [ai.miniforge.event-stream.recovery-location-spec :as location]
            [ai.miniforge.event-stream.workflow-draft-spec :as workflow]
            [ai.miniforge.failure-classifier.interface :as failure]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Size [:and :int [:>= 0]])

(def ^{:stratum 0} Phase [:map [:phase/name :keyword]])

(def ^{:stratum 0} Machine [:map [:machine/state :keyword]])

(def ^{:stratum 0} Failure
  [:map [:error/message :string] [:failure/class failure/FailureClass]])

(def ^{:stratum 0} Resumed
  [:map [:from-state :keyword] [:from-phase :keyword] [:skipping [:vector :keyword]]])

(def ^{:stratum 0} Drift
  [:map [:expected-hash :string] [:actual-hash :string]])

(def ^{:stratum 0} WrittenIdentity
  [:and workflow/ScopeIdentity location/Profile])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} CheckpointWritten
  [:and WrittenIdentity Phase
   [:map [:checkpoint/path :string] [:checkpoint/size-bytes Size]]])

(def ^{:stratum 1} SnapshotWritten
  [:and WrittenIdentity Machine
   [:map [:snapshot/path :string] [:snapshot/size-bytes Size]]])

(def ^{:stratum 1} CheckpointFailed [:and workflow/Identity Phase Failure])

(def ^{:stratum 1} SnapshotFailed [:and workflow/Identity Machine Failure])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Draft
  [:multi {:dispatch :event/type}
    [:workflow/checkpoint-written CheckpointWritten]
    [:workflow/checkpoint-write-failed CheckpointFailed]
    [:workflow/machine-snapshot-written SnapshotWritten]
    [:workflow/machine-snapshot-write-failed SnapshotFailed]
    [:workflow/resumed [:and workflow/Identity Resumed]]
    [:workflow/spec-hash-mismatch [:and workflow/Identity Drift]]])

(comment ::workflow-recovery-contracts)
