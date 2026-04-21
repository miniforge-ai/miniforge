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

(ns ai.miniforge.supervisory-state.emitter
  "Construct and publish `:supervisory/*-upserted` snapshot events — the
   change-notification output of the materialized view built in
   `accumulator.clj`.

   Each entity family has a constructor that produces an N3 §3.19-
   compliant event map and a diff-and-emit function that publishes one
   event per entity that differs between the previous and current table.

   Envelope + sequence numbering is delegated to `event-stream/create-envelope`;
   the emitter does not reach into the stream's internal state."
  (:require
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Event constructors (match N3 §3.19 schemas)

(defn workflow-upserted
  [stream workflow-entity]
  (-> (es/create-envelope stream
                          :supervisory/workflow-upserted
                          (:workflow-run/id workflow-entity)
                          (str "Workflow " (:workflow-run/workflow-key workflow-entity)
                               " upserted"))
      (assoc :supervisory/entity workflow-entity)))

(defn agent-upserted
  [stream agent-entity]
  (-> (es/create-envelope stream
                          :supervisory/agent-upserted
                          nil
                          (str "Agent " (:agent/name agent-entity) " upserted"))
      (assoc :supervisory/entity agent-entity)))

(defn pr-upserted
  [stream pr-entity]
  (-> (es/create-envelope stream
                          :supervisory/pr-upserted
                          nil
                          (str "PR " (:pr/repo pr-entity) "#" (:pr/number pr-entity)
                               " upserted"))
      (assoc :supervisory/entity pr-entity)))

(defn policy-evaluated
  [stream policy-entity]
  (-> (es/create-envelope stream
                          :supervisory/policy-evaluated
                          nil
                          (str "Policy evaluation "
                               (:policy-eval/id policy-entity)
                               ": "
                               (:policy-eval/passed? policy-entity)))
      (assoc :supervisory/entity policy-entity)))

(defn attention-derived
  [stream attention-entity]
  (-> (es/create-envelope stream
                          :supervisory/attention-derived
                          nil
                          (str "Attention "
                               (name (:attention/severity attention-entity)) ": "
                               (:attention/summary attention-entity)))
      (assoc :supervisory/entity attention-entity)))

(defn task-node-upserted
  [stream task-entity]
  (-> (es/create-envelope stream
                          :supervisory/task-node-upserted
                          (:task/workflow-run-id task-entity)
                          (str "Task " (:task/id task-entity)
                               " → " (name (or (:task/kanban-column task-entity) :blocked))))
      (assoc :supervisory/entity task-entity)))

(defn decision-upserted
  [stream decision-entity]
  (-> (es/create-envelope stream
                          :supervisory/decision-upserted
                          (:decision/workflow-run-id decision-entity)
                          (str "Decision " (:decision/id decision-entity)
                               " → " (name (or (:decision/status decision-entity) :pending))))
      (assoc :supervisory/entity decision-entity)))

;------------------------------------------------------------------------------ Layer 1
;; Diff + emit — publish one event per entity that changed

(defn- diff-entries
  "Return keys in `new-m` whose values differ from `old-m`."
  [old-m new-m]
  (for [[k v] new-m
        :when (not= v (get old-m k))]
    k))

(defn- emit-diff!
  [stream ctor old-m new-m]
  (doseq [k (diff-entries old-m new-m)]
    (es/publish! stream (ctor stream (get new-m k)))))

(defn diff-and-emit!
  "Compare `old-table` and `new-table`; publish a snapshot event for each
   entity that was inserted or updated.

   Returns the number of events published, mainly for testing."
  [stream old-table new-table]
  (let [before (count (es/get-events stream))]
    (emit-diff! stream workflow-upserted  (:workflows    old-table) (:workflows    new-table))
    (emit-diff! stream agent-upserted     (:agents       old-table) (:agents       new-table))
    (emit-diff! stream pr-upserted        (:prs          old-table) (:prs          new-table))
    (emit-diff! stream policy-evaluated   (:policy-evals old-table) (:policy-evals new-table))
    (emit-diff! stream attention-derived  (:attention    old-table) (:attention    new-table))
    (emit-diff! stream task-node-upserted (:tasks         old-table) (:tasks        new-table))
    (emit-diff! stream decision-upserted  (:decisions     old-table) (:decisions    new-table))
    (- (count (es/get-events stream)) before)))
