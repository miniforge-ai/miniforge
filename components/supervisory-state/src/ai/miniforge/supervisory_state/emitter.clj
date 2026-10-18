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

   Envelope construction is delegated to `event-stream/create-envelope`.
   The emitter does not allocate sequence numbers or access stream internals."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.messages :as messages]
   [ai.miniforge.supervisory-state.snapshot :as snapshot]))

;------------------------------------------------------------------------------ Layer 0

;; Contract stamp shared by every constructor
(def ^{:stratum 0} ^:private attach-entity snapshot/attach-entity)

;; Diff + emit — publish one event per entity that changed
(defn- ^{:stratum 0} diff-entries
  "Return keys in `new-m` whose values differ from `old-m`."
  [old-m new-m]
  (for [[k v] new-m
        :when (not= v (get old-m k))]
    k))

;------------------------------------------------------------------------------ Layer 1

;; Event constructors (match N3 §3.19 schemas)
(defn ^{:stratum 1} spec-upserted
  [stream spec-entity]
  (-> (es/create-envelope stream
                          :supervisory/spec-upserted
                          nil
                          (messages/t :snapshot/spec {:title (:spec/title spec-entity)}))
      (attach-entity spec-entity)))

(defn ^{:stratum 1} workflow-upserted
  [stream workflow-entity]
  (-> (es/create-envelope stream
                          :supervisory/workflow-upserted
                          (:workflow-run/id workflow-entity)
                          (messages/t :snapshot/workflow {:key (:workflow-run/workflow-key workflow-entity)}))
      (attach-entity workflow-entity)))

(defn ^{:stratum 1} agent-upserted
  [stream agent-entity]
  (-> (es/create-envelope stream
                          :supervisory/agent-upserted
                          nil
                          (messages/t :snapshot/agent {:name (:agent/name agent-entity)}))
      (attach-entity agent-entity)))

(defn ^{:stratum 1} pr-upserted
  [stream pr-entity]
  (-> (es/create-envelope stream
                          :supervisory/pr-upserted
                          nil
                          (messages/t :snapshot/pr {:repo (:pr/repo pr-entity) :number (:pr/number pr-entity)}))
      (attach-entity pr-entity)))

(defn ^{:stratum 1} policy-evaluated
  [stream policy-entity]
  (-> (es/create-envelope stream
                          :supervisory/policy-evaluated
                          nil
                          (messages/t :snapshot/policy {:id (:policy-eval/id policy-entity)
                                                       :passed? (:policy-eval/passed? policy-entity)}))
      (attach-entity policy-entity)))

(defn ^{:stratum 1} attention-derived
  [stream attention-entity]
  (-> (es/create-envelope stream
                          :supervisory/attention-derived
                          nil
                          (messages/t :snapshot/attention {:severity (name (:attention/severity attention-entity))
                                                          :summary (:attention/summary attention-entity)}))
      (attach-entity attention-entity)))

(defn ^{:stratum 1} task-node-upserted
  [stream task-entity]
  (-> (es/create-envelope stream
                          :supervisory/task-node-upserted
                          (:task/workflow-run-id task-entity)
                          (messages/t :snapshot/task {:id (:task/id task-entity)
                                                     :column (name (or (:task/kanban-column task-entity) :blocked))}))
      (attach-entity task-entity)))

(defn ^{:stratum 1} decision-upserted
  [stream decision-entity]
  (-> (es/create-envelope stream
                          :supervisory/decision-upserted
                          (:decision/workflow-run-id decision-entity)
                          (messages/t :snapshot/decision {:id (:decision/id decision-entity)
                                                         :status (name (or (:decision/status decision-entity) :pending))}))
      (attach-entity decision-entity)))

(defn ^{:stratum 1} intervention-upserted
  [stream intervention-entity]
  (-> (es/create-envelope stream
                          :supervisory/intervention-upserted
                          nil
                          (messages/t :snapshot/intervention {:id (:intervention/id intervention-entity)
                                                             :state (name (or (:intervention/state intervention-entity) :proposed))}))
      (attach-entity intervention-entity)))

(defn- ^{:stratum 1} emit-diff!
  [stream ctor old-m new-m]
  (doseq [k (diff-entries old-m new-m)]
    (es/publish! stream (ctor stream (get new-m k)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} diff-and-emit!
  "Compare `old-table` and `new-table`; publish a snapshot event for each
   entity that was inserted or updated.

   Returns the number of events published, mainly for testing."
  [stream old-table new-table]
  (let [before (count (es/get-events stream))]
    (emit-diff! stream spec-upserted      (:specs        old-table) (:specs        new-table))
    (emit-diff! stream workflow-upserted  (:workflows    old-table) (:workflows    new-table))
    (emit-diff! stream agent-upserted     (:agents       old-table) (:agents       new-table))
    (emit-diff! stream pr-upserted        (:prs          old-table) (:prs          new-table))
    (emit-diff! stream policy-evaluated   (:policy-evals old-table) (:policy-evals new-table))
    (emit-diff! stream attention-derived  (:attention    old-table) (:attention    new-table))
    (emit-diff! stream task-node-upserted (:tasks         old-table) (:tasks        new-table))
    (emit-diff! stream decision-upserted  (:decisions     old-table) (:decisions    new-table))
    (emit-diff! stream intervention-upserted (:interventions old-table) (:interventions new-table))
    (- (count (es/get-events stream)) before)))
