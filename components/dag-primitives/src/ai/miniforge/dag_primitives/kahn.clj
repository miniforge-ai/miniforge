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

(ns ai.miniforge.dag-primitives.kahn
  "Generic Kahn's algorithm for topological sort.
   Operates on a plain dependency map — no domain types."
  (:require [clojure.set :as set]
            [ai.miniforge.dag-primitives.graph  :as graph]
            [ai.miniforge.dag-primitives.result :as result]))

;;------------------------------------------------------------------------------ Layer 0
;; Queue construction

(defn- initial-queue
  "Seed the ready queue with all nodes that have no predecessors,
   preserving the caller-supplied tie-breaking order."
  [in-degree ordered-nodes]
  (into clojure.lang.PersistentQueue/EMPTY
        (filter #(zero? (get in-degree %)) ordered-nodes)))

;;------------------------------------------------------------------------------ Layer 1
;; Kahn step

(defn- process-node
  "Remove node from the queue, decrement in-degrees for its successors,
   and enqueue any successor whose in-degree just reached zero."
  [adj in-degree queue result node]
  (let [succs   (get adj node #{})
        new-deg (reduce #(update %1 %2 dec) in-degree succs)
        ready   (filter #(zero? (get new-deg %)) succs)]
    [(into (pop queue) ready) new-deg (conj result node)]))

;;------------------------------------------------------------------------------ Layer 2
;; Topological sort

(defn topological-sort
  "Kahn's algorithm on a generic dependency map.

   dep-map: {node-id #{predecessor-node-id ...}}
     Each key is a node; its value is the set of nodes that must precede it.
     Nodes with an empty (or absent) predecessor set have no dependencies.

   ordered-nodes (optional): sequence of all nodes in preferred initial-queue order.
     When two nodes are both ready at the same time, they are dequeued in the order
     they appear in ordered-nodes. If omitted, (keys dep-map) order is used.
     Providing an explicit ordered-nodes makes tie-breaking deterministic.

   Returns:
     (result/ok  [node-ids in topological order])
     (result/err :cycle-detected message {:cycle-nodes #{...}})"
  ([dep-map]
   (topological-sort dep-map (keys dep-map)))
  ([dep-map ordered-nodes]
   (let [all-nodes (set (keys dep-map))
         adj       (graph/successors-of dep-map)
         in-degree (graph/predecessor-counts dep-map)]
     (loop [queue  (initial-queue in-degree ordered-nodes)
            in-deg in-degree
            result []]
       (if (empty? queue)
         (if (= (count result) (count all-nodes))
           (result/ok result)
           (result/err :cycle-detected
                       "Dependency cycle detected"
                       {:cycle-nodes (set/difference all-nodes (set result))}))
         (let [node                     (peek queue)
               [queue' in-deg' result'] (process-node adj in-deg queue result node)]
           (recur queue' in-deg' result')))))))
