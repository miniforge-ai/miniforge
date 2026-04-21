;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.supervisory-state.core-test
  "End-to-end tests for the supervisory-state component lifecycle:
   replay, live subscription, snapshot emission, and re-emit prevention."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.core :as es-core]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.core :as core]
   [ai.miniforge.supervisory-state.interface :as iface]))

;------------------------------------------------------------------------------ Helpers

(defn- no-sink-stream []
  (es-core/create-event-stream {:sinks []}))

(defn- supervisory-events
  "Events on the stream with type in the `:supervisory/*` family."
  [stream]
  (->> (es/get-events stream)
       (filter #(some-> % :event/type namespace (= "supervisory")))))

(defn- workflow-started [wf-id]
  {:event/type :workflow/started
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :event/sequence-number 0
   :workflow/id wf-id
   :message "Workflow started"})

(defn- workflow-completed [wf-id]
  {:event/type :workflow/completed
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"
   :event/sequence-number 1
   :workflow/id wf-id
   :message "Workflow completed"})

;------------------------------------------------------------------------------ Lifecycle

(deftest start-then-stop-subscribes-and-unsubscribes
  (let [stream (no-sink-stream)
        comp   (iface/create stream)]
    (iface/start! comp)
    (is (true? (:subscribed? @comp)))
    (is (contains? (:subscribers @stream) core/subscriber-id))
    (iface/stop! comp)
    (is (false? (:subscribed? @comp)))
    (is (not (contains? (:subscribers @stream) core/subscriber-id)))))

(deftest attach!-is-create-plus-start!
  (let [stream (no-sink-stream)
        comp   (iface/attach! stream)]
    (is (true? (:subscribed? @comp))
        "attach! returns a component that is already subscribed")
    (is (contains? (:subscribers @stream) core/subscriber-id)
        "attach! registers the stream subscription identically to create+start!")
    (iface/stop! comp)))

;------------------------------------------------------------------------------ Emission

(deftest live-workflow-events-produce-supervisory-snapshots
  (let [stream (no-sink-stream)
        comp   (iface/create stream)
        wf-id  (random-uuid)]
    (iface/start! comp)
    (es/publish! stream (workflow-started wf-id))
    (es/publish! stream (workflow-completed wf-id))
    (let [snaps   (supervisory-events stream)
          kinds   (frequencies (map :event/type snaps))]
      (is (>= (count snaps) 2)
          "at least one supervisory/workflow-upserted per entity change")
      (is (some #{:supervisory/workflow-upserted} (keys kinds)))
      ;; attention may also fire (workflow completed → :info item)
      )))

(deftest supervisory-events-are-not-re-emitted
  (let [stream (no-sink-stream)
        comp   (iface/create stream)]
    (iface/start! comp)
    (es/publish! stream (workflow-started (random-uuid)))
    (let [before (count (supervisory-events stream))]
      ;; Publish a synthetic supervisory event directly; handle-event! must
      ;; ignore it so the component doesn't recurse.
      (es/publish! stream {:event/type :supervisory/workflow-upserted
                           :event/id (random-uuid)
                           :event/timestamp (java.util.Date.)
                           :event/version "1.0.0"
                           :event/sequence-number 99
                           :workflow/id (random-uuid)
                           :message "synthetic"
                           :supervisory/entity {:workflow-run/id (random-uuid)}})
      (let [after (count (supervisory-events stream))]
        (is (= (inc before) after)
            "only the synthetic event should be added; no re-emission loop")))))

;------------------------------------------------------------------------------ Replay

(deftest startup-replay-reconstructs-table-from-history
  (let [stream (no-sink-stream)
        wf-id  (random-uuid)]
    ;; Events exist in the stream before the component starts.
    (es/publish! stream (workflow-started wf-id))
    (es/publish! stream (workflow-completed wf-id))
    (let [comp (iface/create stream)]
      (iface/start! comp)
      (let [runs (iface/workflows comp)]
        (is (= 1 (count runs)))
        (is (= :completed (:workflow-run/status (first runs)))
            "replay must apply all historic events, ending in :completed")))))

;------------------------------------------------------------------------------ TaskNode (N5-δ3 §3.3)

(defn- task-state-changed [tid wf-id to-state & [context]]
  (cond-> {:event/type :task/state-changed
           :event/id (random-uuid)
           :event/timestamp (java.util.Date.)
           :event/version "1.0.0"
           :event/sequence-number 0
           :workflow/id wf-id
           :task/id tid
           :task/to-state to-state
           :message (str "Task " tid " → " (name to-state))}
    context (assoc :task/context context)))

(deftest task-state-changed-produces-supervisory-task-node-upserted
  (let [stream (no-sink-stream)
        comp   (iface/create stream)
        tid    (random-uuid)
        wf-id  (random-uuid)]
    (iface/start! comp)
    (es/publish! stream (task-state-changed tid wf-id :running
                                            {:description "Implement X" :type :implement}))
    (let [snaps (->> (supervisory-events stream)
                     (filter #(= :supervisory/task-node-upserted (:event/type %))))]
      (is (= 1 (count snaps)))
      (let [entity (:supervisory/entity (first snaps))]
        (is (= tid (:task/id entity)))
        (is (= wf-id (:task/workflow-run-id entity)))
        (is (= :running (:task/status entity)))
        (is (= :active (:task/kanban-column entity)))
        (is (= "Implement X" (:task/description entity)))))))

(deftest task-transitions-emit-one-snapshot-per-change
  (let [stream (no-sink-stream)
        comp   (iface/create stream)
        tid    (random-uuid)
        wf-id  (random-uuid)]
    (iface/start! comp)
    (doseq [state [:pending :ready :running :completed]]
      (es/publish! stream (task-state-changed tid wf-id state)))
    (let [snaps (->> (supervisory-events stream)
                     (filter #(= :supervisory/task-node-upserted (:event/type %))))
          final (last snaps)]
      ;; One snapshot per distinct status transition (diff-based emit).
      (is (= 4 (count snaps)) "four transitions → four snapshots")
      (is (= :completed (:task/status (:supervisory/entity final))))
      (is (= :done (:task/kanban-column (:supervisory/entity final))))
      (is (some? (:task/completed-at (:supervisory/entity final))))
      (is (some? (:task/elapsed-ms (:supervisory/entity final)))))))
