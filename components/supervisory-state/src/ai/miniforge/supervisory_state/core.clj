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
  "Supervisory-state component lifecycle and runtime.

   The component owns an atom of form:

     {:table <EntityTable>     ; canonical entity snapshot
      :last-seq long            ; highest sequence number observed
      :subscriber-id keyword    ; event-stream subscription handle
      :stream <event-stream>}   ; back-reference to the stream we emit into

   On `start!` it first replays the stream's in-memory event log to rebuild
   the entity table (N5-delta-1 §3.4), then subscribes live. Each live event
   is passed through the accumulator; if the entity table changes, the
   emitter publishes one `:supervisory/*-upserted` event per changed entity
   back into the same stream."
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
;; Startup replay

(defn- replay
  "Walk the stream's in-memory event log and fold it through the accumulator.
   Returns a fresh entity table. Attention is derived once at the end rather
   than on every step, to avoid emitting thousands of intermediate items
   during replay."
  [stream]
  (let [events (es/get-events stream)]
    (-> (accumulator/apply-events schema/empty-table events)
        with-attention)))

;------------------------------------------------------------------------------ Layer 3
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
               :supervisory/attention-derived}
             (:event/type event))
    (swap! component tick event)))

(defn start!
  "Replay the stream's history into the entity table, then subscribe live.
   Idempotent — subsequent calls are no-ops."
  [component]
  (let [{:keys [stream subscribed?]} @component]
    (when-not subscribed?
      (let [initial (replay stream)]
        (swap! component assoc :table initial :subscribed? true))
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
