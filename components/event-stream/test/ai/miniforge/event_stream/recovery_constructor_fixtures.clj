;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-constructor-fixtures
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.recovery-draft-fixtures :as contract]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} cases
  [[:workflow/checkpoint-written events/checkpoint-written
    [:phase/name :checkpoint/path :checkpoint/size-bytes]]
   [:workflow/checkpoint-write-failed events/checkpoint-write-failed [:phase/name ::payload]]
   [:workflow/machine-snapshot-written events/machine-snapshot-written
    [:machine/state :snapshot/path :snapshot/size-bytes]]
   [:workflow/machine-snapshot-write-failed events/machine-snapshot-write-failed
    [:machine/state ::payload]]
   [:workflow/resumed events/workflow-resumed [:from-state :from-phase :skipping]]
   [:workflow/spec-hash-mismatch events/workflow-spec-hash-mismatch [:expected-hash :actual-hash]]])

(defn ^{:stratum 0} construct [stream workflow-id [event-type constructor fields]]
  (let [payload (get (contract/payloads) event-type)
        args (mapv #(if (= ::payload %) payload (get payload %)) fields)]
    (apply constructor stream workflow-id args)))
