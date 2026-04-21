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

(ns ai.miniforge.supervisory-state.core
  "Supervisory-state — materialized view of the event log with change
   notifications.

   The component maintains an in-memory entity table derived from events
   flowing through the event stream. When the table changes, the emitter
   publishes `:supervisory/*-upserted` events on the same stream so that
   downstream consumers (dashboard, TUI, file sink) can subscribe to
   canonical entity snapshots instead of folding source events themselves.

   The runtime atom is not a store — it's a view cache. No persistence
   across process restarts; the view is rebuilt by normal live event
   flow after attach.

   Shape:
     {:table       <EntityTable>  — current view
      :stream      <event-stream> — the stream we subscribe to + emit into
      :subscribed? boolean        — idempotent-start latch}"
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.accumulator :as accumulator]
   [ai.miniforge.supervisory-state.attention :as attention]
   [ai.miniforge.supervisory-state.emitter :as emitter]
   [ai.miniforge.supervisory-state.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Subscriber id

(def ^:const subscriber-id ::supervisory-state)

;------------------------------------------------------------------------------ Layer 1
;; Tick — process a single event; derive attention; emit diffs

(defn- with-attention
  "Attach derived AttentionItems into the table so the emitter can diff/emit
   them alongside entity upserts."
  [table]
  (assoc table :attention (attention/derive-items table)))

(defn tick
  "Apply one event to `state`, emit diffs. Pure at the entity-table level;
   side-effects are limited to `es/publish!` calls on the stream.

   Returns the updated state."
  [{:keys [table stream] :as state} event]
  (let [next-table (-> table
                       (accumulator/apply-event event)
                       with-attention)]
    (when (not= table next-table)
      (emitter/diff-and-emit! stream table next-table))
    (assoc state :table next-table)))

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle

(defn create
  "Build a fresh supervisory-state component instance bound to `stream`.
   Does not subscribe yet — call `start!` to begin consuming events."
  [stream]
  (atom {:table  schema/empty-table
         :stream stream
         :subscribed? false}))

(defn- handle-event!
  [component event]
  ;; Ignore our own snapshot events to prevent re-emit loops.
  (when-not (#{:supervisory/workflow-upserted
               :supervisory/agent-upserted
               :supervisory/pr-upserted
               :supervisory/policy-evaluated
               :supervisory/attention-derived
               :supervisory/task-node-upserted
               :supervisory/decision-upserted}
             (:event/type event))
    (swap! component tick event)))

(defn start!
  "Subscribe to the stream. Idempotent — subsequent calls are no-ops.

   The materialized view starts empty and accumulates as events flow
   live. No startup replay of the in-memory event log (YAGNI — no
   production caller attached to a pre-populated stream)."
  [component]
  (let [{:keys [stream subscribed?]} @component]
    (when-not subscribed?
      (swap! component assoc :subscribed? true)
      (es/subscribe! stream subscriber-id
                     (fn [event] (handle-event! component event))))
    component))

(defn stop!
  "Unsubscribe from the stream. The entity table is retained so it can be
   queried post-shutdown."
  [component]
  (let [{:keys [stream subscribed?]} @component]
    (when subscribed?
      (es/unsubscribe! stream subscriber-id)
      (swap! component assoc :subscribed? false))
    component))

(defn attach!
  "Convenience: create a component bound to `stream` and start it in one step.

   Composition of [[create]] + [[start!]] — kept here in `core` so
   `interface.clj` stays a pure pass-through surface per the Polylith
   `interface.clj` contract."
  [stream]
  (start! (create stream)))

;------------------------------------------------------------------------------ Layer 4
;; Query API

(defn table
  "Current entity table snapshot (pure read)."
  [component]
  (:table @component))

(defn workflows   [component] (vals (get-in @component [:table :workflows])))
(defn agents      [component] (vals (get-in @component [:table :agents])))
(defn prs         [component] (vals (get-in @component [:table :prs])))
(defn policy-evals [component] (vals (get-in @component [:table :policy-evals])))
(defn attention   [component] (vals (get-in @component [:table :attention])))
