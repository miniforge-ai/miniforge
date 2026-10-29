;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-fields-spec
  "Distinct scheduler liveness and substantive progress facts from N3 §3.1.1."
  (:require [ai.miniforge.event-stream.workflow-draft-spec :as workflow]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} payload-version "2.0.0")

(def ^{:stratum 0} PositiveLong [:and int? [:> 0]])

(def ^{:stratum 0} Absent [:not :any])

(defn ^{:stratum 0} consistent-progress? [event]
  (let [gap (:phase/progress-gap-ms event)
        elapsed (:heartbeat/elapsed-ms event)]
    (case (:phase/progress-status event)
      :unknown true
      :none (= gap elapsed)
      :observed (and (int? gap) (int? elapsed) (<= gap elapsed))
      false)))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Liveness
  [:map
   [:phase/active-since inst?]
   [:heartbeat/activation-id :uuid]
   [:heartbeat/sequence PositiveLong]
   [:heartbeat/observed-at inst?]
   [:heartbeat/interval-ms PositiveLong]
   [:heartbeat/elapsed-ms workflow/Duration]
   [:phase/events-emitted {:optional true} Absent]
   [:phase/last-event-at {:optional true} Absent]
   [:phase/gap-since-last-event-ms {:optional true} Absent]])

(def ^{:stratum 1} Progress
  [:multi {:dispatch :phase/progress-status}
   [:none [:map
           [:phase/progress-count [:= 0]]
           [:phase/last-progress-at {:optional true} Absent]
           [:phase/progress-gap-ms workflow/Duration]]]
   [:observed [:map
               [:phase/progress-count PositiveLong]
               [:phase/last-progress-at inst?]
               [:phase/progress-gap-ms workflow/Duration]]]
   [:unknown [:map
              [:phase/progress-count {:optional true} Absent]
              [:phase/last-progress-at {:optional true} Absent]
              [:phase/progress-gap-ms {:optional true} Absent]]]])

(comment ::heartbeat-contract)
