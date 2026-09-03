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
(ns ai.miniforge.deliberation-workspace.guards
  "The abuse guards of the N14 validation pipeline: hard-constraint
   immutability (§2.4), the anti-livelock rules (§3.5), and the idempotency
   check (§3.4). Composes onto the concurrency stages.

   Open challenges live in `:workspace/challenges` rather than in the object
   graph: N14's object taxonomy (§2.1) is closed and has no challenge type,
   and a challenge is a transaction record, not an object. It stays derived
   state, reconstructible from the log."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-challenge-limit
  "Open challenges one role may hold against one object before further
   challenges are refused (N14 §3.5). Manifest-configurable."
  2)

(defn ^{:stratum 0} open-challenges-by
  "Count of unresolved challenges `role` holds against `target-id`."
  [workspace role target-id]
  (->> (get workspace :workspace/challenges {})
       vals
       (filter #(and (= role (:challenge/role %))
                     (= target-id (:challenge/target %))
                     (= :open (:challenge/status %))))
       count))

(defn- ^{:stratum 0} backed?
  "N14 §3.5: a challenge must carry evidence, or ride in the same transaction
   as an experiment that discriminates what it challenges. Bare objections are
   refused — that rule is what stops a skeptic looping forever.

   `:evidence` and a sibling's `:discriminates` are read unscreened.
   `validation/concurrency-stages` establishes that both name object ids
   before any guard runs, which is why the guards must be composed onto that
   chain rather than run alone."
  [operation siblings]
  (let [targets (tx/touched-ids operation)
        discriminates? (fn [sibling]
                         (and (= :propose-experiment (:op sibling))
                              (some (set (get sibling :discriminates #{})) targets)))]
    (boolean (or (seq (get operation :evidence #{}))
                 (some discriminates? siblings)))))

(defn- ^{:stratum 0} committed-already?
  "True when `role` already committed an identical operation against the same
   targets (N14 §3.4 idempotency)."
  [workspace operation role]
  (->> (get workspace :workspace/log [])
       (filter #(= role (:tx/role %)))
       (mapcat :tx/operations)
       (some #(and (= (:op %) (:op operation))
                   (= (tx/touched-ids %) (tx/touched-ids operation))))
       boolean))

(defn- ^{:stratum 0} check-hard-constraints
  "N14 §2.4: no agent transaction may modify or retire a :hard constraint.
   Hard constraints enter only from the specification or the user via OCI."
  [workspace operation _context]
  (let [known (validation/objects-of workspace)
        touched (keep known (tx/touched-ids operation))]
    (when-let [hard (seq (filter object/hard-constraint? touched))]
      (validation/reject
       :unauthorized :anomalies.deliberation/hard-constraint-immutable
       "Hard constraints are immutable to agent transactions"
       {:op (:op operation) :targets (mapv :object/id hard)}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-idempotency
  [workspace operation {:keys [role]}]
  (when (committed-already? workspace operation role)
    (validation/reject
     :conflict :anomalies.deliberation/duplicate-operation
     "Role already committed this operation against these targets"
     {:op (:op operation) :role role})))

(defn- ^{:stratum 1} check-anti-livelock
  [workspace operation {:keys [role siblings]}]
  (when (= :challenge (:op operation))
    (let [limit (get workspace :workspace/challenge-limit default-challenge-limit)
          saturated (filter #(>= (open-challenges-by workspace role %) limit)
                            (tx/touched-ids operation))]
      (cond
        (not (backed? operation siblings))
        (validation/reject
         :invalid-input :anomalies.deliberation/bare-challenge
         "A challenge must carry evidence or a discriminating experiment (N14 §3.5)"
         {:targets (tx/touched-ids operation)})

        (seq saturated)
        (validation/reject
         :exhausted :anomalies.deliberation/challenge-limit
         "Role already holds the permitted number of open challenges on this object"
         {:role role :targets (set saturated) :limit limit})))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} guard-stages
  "Abuse guards. Run after the concurrency stages: compose the full N14 §3.4
   pipeline as `(into validation/concurrency-stages guards/guard-stages)`."
  [check-hard-constraints check-anti-livelock check-idempotency])
