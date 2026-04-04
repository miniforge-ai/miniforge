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

(ns ai.miniforge.dag-primitives.graph
  "Utility functions for directed graphs represented as dependency maps.
   dep-map convention: {node-id #{predecessor-id ...}}")

;;------------------------------------------------------------------------------ Layer 0
;; Graph construction

(defn successors-of
  "Build a forward adjacency map from a dependency map.
   For each node, returns the set of nodes that directly follow it.
   Every node in dep-map gets an entry, even if it has no successors."
  [dep-map]
  (reduce (fn [adj [node predecessors]]
            (reduce (fn [adj predecessor]
                      (update adj predecessor (fnil conj #{}) node))
                    adj
                    predecessors))
          (zipmap (keys dep-map) (repeat #{}))
          dep-map))

(defn predecessor-counts
  "Return a map of {node-id count} counting unsatisfied predecessors per node."
  [dep-map]
  (reduce-kv (fn [counts node predecessors]
               (assoc counts node (count predecessors)))
             {}
             dep-map))
