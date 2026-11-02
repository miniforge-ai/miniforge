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
(ns ai.miniforge.deliberation-workspace.admission
  "The trusted admission boundary (N14 §0.5, §3.4). The runtime supplies the
   activation context; the proposal supplies only operations. Operations are
   validated in order against a private candidate, so each sees what earlier
   ones created, and the transaction publishes as one version or not at all."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.commit :as commit]
   [ai.miniforge.deliberation-workspace.guards :as guards]
   [ai.miniforge.deliberation-workspace.messages :as messages]
   [ai.miniforge.deliberation-workspace.provenance :as provenance]
   [ai.miniforge.deliberation-workspace.semantics :as semantics]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} max-operations
  "Operations one proposal may carry. Bounds the work one activation can
   push through admission; far above what a single work turn produces."
  128)

(def ^{:stratum 0} proposal-keys
  "The only fields a proposal may carry. `:tx/role` is tolerated when it
   repeats the runtime's assignment; identity, activation and basis are
   never accepted from a proposal."
  #{:tx/operations :tx/role})

(def ^{:stratum 0} operation-stages
  "Stages each operation runs against the candidate."
  (-> validation/graph-stages
      (into semantics/semantic-stages)
      (into provenance/provenance-stages)
      (into guards/guard-stages)))

(defn- ^{:stratum 0} participant? [participant]
  (or (keyword? participant)
      (and (string? participant) (not (str/blank? participant)))))

(defn- ^{:stratum 0} context-valid?
  "True when the runtime-supplied context names a known responsibility, a
   participant, an activation, and a basis the workspace has reached."
  [workspace context]
  (and (map? context)
       (let [{:keys [role participant activation basis]} context]
         (and (contains? tx/responsibilities role)
              (participant? participant)
              (string? activation) (not (str/blank? activation))
              (nat-int? basis)
              (<= basis (get workspace :workspace/version 0))))))

(defn- ^{:stratum 0} impersonating?
  "True when `proposal` carries a field outside `proposal-keys` or names a
   responsibility other than the one the runtime assigned."
  [proposal role]
  (or (seq (remove proposal-keys (keys proposal)))
      (and (contains? proposal :tx/role) (not= role (:tx/role proposal)))))

(defn- ^{:stratum 0} operation-count-valid? [operations]
  (and (sequential? operations)
       (<= 1 (count operations) max-operations)))

(defn- ^{:stratum 0} transaction
  "The transaction the runtime records: the proposal's operations under the
   runtime's identity, activation and basis."
  [proposal {:keys [role participant activation basis]}]
  {:tx/role role
   :tx/participant participant
   :tx/activation activation
   :tx/basis basis
   :tx/operations (vec (:tx/operations proposal))})

(defn- ^{:stratum 0} refuse [type subtype message]
  (validation/reject type subtype message {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} shape-rejection
  "The rejection for a context or proposal the boundary cannot accept, or nil."
  [workspace proposal context]
  (cond
    (not (context-valid? workspace context))
    (refuse :unauthorized :anomalies.deliberation/invalid-context
            (messages/system-t :anomaly/invalid-context))

    (not (map? proposal))
    (refuse :invalid-input :anomalies.deliberation/invalid-transaction
            (messages/system-t :anomaly/invalid-transaction))

    (impersonating? proposal (:role context))
    (refuse :unauthorized :anomalies.deliberation/actor-mismatch
            (messages/system-t :anomaly/actor-mismatch))

    (not (operation-count-valid? (:tx/operations proposal)))
    (refuse :invalid-input :anomalies.deliberation/operation-count
            (messages/system-t :anomaly/operation-count))))

(defn- ^{:stratum 1} candidate-step
  "Validate `operation` against `candidate` and fold it in, or reduce to the
   rejection. Basis is the candidate's own version: the transaction's
   original basis was already checked, and its own earlier writes are not
   staleness."
  [transaction candidate operation]
  (let [basis (get candidate :workspace/version 0)
        context {:role (:tx/role transaction)
                 :participant (:tx/participant transaction)
                 :basis basis
                 :siblings (:tx/operations transaction)}
        single (assoc transaction :tx/operations [operation] :tx/basis basis)]
    (if-let [rejection (validation/validate-operation candidate operation context
                                                      operation-stages)]
      (reduced rejection)
      (commit/stage candidate single))))

(defn- ^{:stratum 1} sequential-rejection
  "The first rejection when the operations are applied one at a time to a
   private candidate, or nil."
  [workspace transaction]
  (let [result (reduce #(candidate-step transaction %1 %2)
                       workspace
                       (:tx/operations transaction))]
    (when (anomaly/anomaly? result) result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} admit
  "Admit `proposal` under the runtime-supplied `context`
   (`:role :participant :activation :basis`). Returns
   `{:workspace :transaction}` with the transaction committed as one
   version, or a rejection anomaly with the workspace untouched."
  [workspace proposal context]
  (or (shape-rejection workspace proposal context)
      (let [tx (transaction proposal context)]
        (or (validation/validate workspace tx validation/payload-stages)
            (validation/validate workspace tx validation/basis-stages)
            (sequential-rejection workspace tx)
            {:workspace (commit/commit workspace tx)
             :transaction tx}))))
