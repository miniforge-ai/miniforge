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
  "Construct and publish `:supervisory/*-upserted` snapshot events.

   Each entity family has a constructor that produces an N3 §3.19-compliant
   event map and a diff-and-emit function that publishes one event per
   entity that differs between the previous and current table."
  (:require
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Event constructors (match N3 §3.19 schemas)
;;
;; `create-envelope` in `event-stream.core` is not on the interface, so this
;; component builds its own envelope inline. The `publish!` path only reads
;; the N3 §2.1 required fields and the `:event/type` for routing.

(def ^:const event-version "1.0.0")

(defn- next-seq!
  "Increment and return the sequence number for a given scope id."
  [stream scope-id]
  (let [new-val (-> (swap! stream update-in [:sequence-numbers scope-id]
                           (fn [v] (inc (or v -1))))
                    (get-in [:sequence-numbers scope-id]))]
    new-val))

(defn- event-envelope
  [stream event-type scope-id message]
  {:event/type             event-type
   :event/id               (random-uuid)
   :event/timestamp        (java.util.Date.)
   :event/version          event-version
   :event/sequence-number  (next-seq! stream scope-id)
   :workflow/id            scope-id
   :message                message})

(defn workflow-upserted
  [stream workflow-entity]
  (-> (event-envelope stream
                      :supervisory/workflow-upserted
                      (:workflow-run/id workflow-entity)
                      (str "Workflow " (:workflow-run/workflow-key workflow-entity)
                           " upserted"))
      (assoc :supervisory/entity workflow-entity)))

(defn agent-upserted
  [stream agent-entity]
  (-> (event-envelope stream
                      :supervisory/agent-upserted
                      nil
                      (str "Agent " (:agent/name agent-entity) " upserted"))
      (assoc :supervisory/entity agent-entity)))

(defn pr-upserted
  [stream pr-entity]
  (-> (event-envelope stream
                      :supervisory/pr-upserted
                      nil
                      (str "PR " (:pr/repo pr-entity) "#" (:pr/number pr-entity)
                           " upserted"))
      (assoc :supervisory/entity pr-entity)))

(defn policy-evaluated
  [stream policy-entity]
  (-> (event-envelope stream
                      :supervisory/policy-evaluated
                      nil
                      (str "Policy evaluation "
                           (:policy-eval/id policy-entity)
                           ": "
                           (:policy-eval/passed? policy-entity)))
      (assoc :supervisory/entity policy-entity)))

(defn attention-derived
  [stream attention-entity]
  (-> (event-envelope stream
                      :supervisory/attention-derived
                      nil
                      (str "Attention "
                           (name (:attention/severity attention-entity)) ": "
                           (:attention/summary attention-entity)))
      (assoc :supervisory/entity attention-entity)))

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
  (let [before (count (:events @stream))]
    (emit-diff! stream workflow-upserted  (:workflows    old-table) (:workflows    new-table))
    (emit-diff! stream agent-upserted     (:agents       old-table) (:agents       new-table))
    (emit-diff! stream pr-upserted        (:prs          old-table) (:prs          new-table))
    (emit-diff! stream policy-evaluated   (:policy-evals old-table) (:policy-evals new-table))
    (emit-diff! stream attention-derived  (:attention    old-table) (:attention    new-table))
    (- (count (:events @stream)) before)))
