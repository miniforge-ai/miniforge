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
(ns ai.miniforge.deliberation-workspace.validation
  "The concurrency stages of the N14 §3.4 transaction validation pipeline:
   schema conformance, target existence, role permission, and basis
   staleness. Rejections are anomalies as data — a routable value the
   scheduler logs, never an exception.

   The abuse guards (hard-constraint immutability, anti-livelock,
   idempotency) compose onto this chain in a sibling namespace."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} reject
  "Build a rejection anomaly carrying a domain subtype, so the scheduler can
   route on the reason without parsing a message (N14 §3.4)."
  [type subtype message data]
  (anomaly/sub-anomaly type subtype message data))

(defn ^{:stratum 0} objects-of
  "The object graph of `workspace`."
  [workspace]
  (get workspace :workspace/objects {}))

(defn ^{:stratum 0} validate-operation
  "Run `stages` against one operation in order, returning the first anomaly
   or nil. `context` carries :role and :basis from the enclosing transaction."
  [workspace operation context stages]
  (some #(% workspace operation context) stages))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-schema [_workspace operation _context]
  (when-not (tx/known-operation? (:op operation))
    (reject :invalid-input :anomalies.deliberation/unknown-operation
            "Operation is outside the closed N14 §3.2 vocabulary"
            {:op (:op operation)})))

(defn- ^{:stratum 1} check-targets [workspace operation _context]
  (let [known (objects-of workspace)
        missing (remove #(contains? known %) (tx/touched-ids operation))]
    (when (seq missing)
      (reject :not-found :anomalies.deliberation/unknown-target
              "Operation targets objects that do not exist"
              {:op (:op operation) :missing (set missing)}))))

(defn- ^{:stratum 1} check-permission [_workspace operation {:keys [role]}]
  (when-not (tx/permitted? role (:op operation))
    (reject :unauthorized :anomalies.deliberation/role-forbidden
            "Role may not propose this operation (N14 §5.3)"
            {:op (:op operation) :role role})))

(defn- ^{:stratum 1} check-basis
  "Basis staleness per concurrency class (N14 §3.3). Additive operations
   commute, so they commit on a stale basis provided their targets are still
   live; mergeable and exclusive operations require targets untouched since
   the basis version the projection was rendered from."
  [workspace operation {:keys [basis]}]
  (let [known (objects-of workspace)
        targets (keep known (tx/touched-ids operation))]
    (cond
      (not (number? basis))
      (reject :invalid-input :anomalies.deliberation/missing-basis
              "Transaction must declare the workspace version its projection was rendered from"
              {:op (:op operation) :basis basis})

      (= :additive (tx/class-of (:op operation)))
      (when-let [terminal (seq (filter object/terminal? targets))]
        (reject :conflict :anomalies.deliberation/terminal-target
                "Operation targets an object already in a terminal status"
                {:op (:op operation) :targets (mapv :object/id terminal)}))

      :else
      (when-let [moved (seq (filter #(> (:object/touched-at %) basis) targets))]
        (reject :conflict :anomalies.deliberation/stale-basis
                "Object changed since the projection this transaction was built from"
                {:op (:op operation) :basis basis
                 :targets (mapv :object/id moved)})))))

(defn ^{:stratum 1} validate
  "Validate a whole transaction against `stages`. Returns nil when every
   operation passes, or the first anomaly. A rejected transaction is
   discarded whole (N14 §3.4) — partial application would leave the
   workspace in a state no activation ever proposed.

   Stages are supplied by the caller rather than defaulted, so the abuse
   guards extend the chain without reopening this namespace."
  [workspace transaction stages]
  (let [operations (:tx/operations transaction)
        context {:role (:tx/role transaction)
                 :basis (:tx/basis transaction)
                 :siblings operations}]
    (some #(validate-operation workspace % context stages) operations)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} concurrency-stages
  "Ordered §3.4 stages. Schema conformance runs first because every later
   stage reads the operation vocabulary."
  [check-schema check-targets check-permission check-basis])
