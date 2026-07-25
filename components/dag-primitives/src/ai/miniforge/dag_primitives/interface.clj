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

;------------------------------------------------------------------------------ Layer 0

;; Topological sort
(defn ^{:stratum 0} topological-sort
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

;; Result monad
(defn ^{:stratum 0} ok        [data]              (result/ok data))

(defn ^{:stratum 0} err
  ([code message]      (result/err code message))
  ([code message data] (result/err code message data)))

(defn ^{:stratum 0} ok?       [r]                 (result/ok? r))

(defn ^{:stratum 0} err?      [r]                 (result/err? r))

(defn ^{:stratum 0} unwrap    [r]                 (result/unwrap r))

(defn ^{:stratum 0} unwrap-anomaly [r]            (result/unwrap-anomaly r))

(defn ^{:stratum 0} unwrap-or [r default]         (result/unwrap-or r default))

(defn ^{:stratum 0} map-ok    [r f]               (result/map-ok r f))

(defn ^{:stratum 0} map-err   [r f]               (result/map-err r f))

(defn ^{:stratum 0} and-then  [r f]               (result/and-then r f))

(defn ^{:stratum 0} or-else   [r f]               (result/or-else r f))

(defn ^{:stratum 0} collect   [results]           (result/collect results))
