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

(ns ai.miniforge.dag-primitives.interface
  "Public API for dag-primitives — generic topological sort and Result monad."
  (:require [ai.miniforge.dag-primitives.kahn   :as kahn]
            [ai.miniforge.dag-primitives.result :as result]))

;;------------------------------------------------------------------------------ Layer 0
;; Topological sort

(defn topological-sort
  "Kahn's algorithm on a generic dependency map.

   dep-map: {node-id #{predecessor-node-id ...}}

   Optional ordered-nodes: sequence of all nodes in preferred tie-breaking order.
   Providing this makes results deterministic when nodes have no ordering constraint.

   Returns:
     {:ok? true  :data [node-ids in topological order]}
     {:ok? false :error {:code :cycle-detected :cycle-nodes #{...}}}"
  ([dep-map]
   (kahn/topological-sort dep-map))
  ([dep-map ordered-nodes]
   (kahn/topological-sort dep-map ordered-nodes)))

;;------------------------------------------------------------------------------ Layer 1
;; Result monad

(defn ok        [data]              (result/ok data))
(defn err
  ([code message]      (result/err code message))
  ([code message data] (result/err code message data)))
(defn ok?       [r]                 (result/ok? r))
(defn err?      [r]                 (result/err? r))
(defn unwrap    [r]                 (result/unwrap r))
(defn unwrap-or [r default]         (result/unwrap-or r default))
(defn map-ok    [r f]               (result/map-ok r f))
(defn map-err   [r f]               (result/map-err r f))
(defn and-then  [r f]               (result/and-then r f))
(defn or-else   [r f]               (result/or-else r f))
(defn collect   [results]           (result/collect results))
