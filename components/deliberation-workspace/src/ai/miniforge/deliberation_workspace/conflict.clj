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
(ns ai.miniforge.deliberation-workspace.conflict
  "Engine-derived conflicts (N14 §2.5).

   Conflicts are never proposed by an activation and are never closed by
   deletion — only by a transaction that resolves or supersedes one of the
   participants."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} conflict-id-of [[id target]]
  (str "conflict-" id "-" target))

(defn- ^{:stratum 0} resolve-conflicts
  "Mark `:resolved` each open conflict outside `active-ids`: a transaction
   resolved or superseded one of its participants. Every status that ends
   a participant's liveness is terminal, so a resolved conflict never
   reopens; it stays in the graph as history."
  [workspace active-ids version]
  (reduce-kv (fn [ws id o]
               (if (and (= :conflict (:object/type o))
                        (= :open (:object/status o))
                        (not (contains? active-ids id)))
                 (assoc-in ws [:workspace/objects id]
                           (-> o (assoc :object/status :resolved) (object/touch version)))
                 ws))
             workspace
             (get workspace :workspace/objects {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} derive-conflicts
  "N14 §2.5: a conflict object is derived for every pair of live objects
   linked `contradicts` — any object that is not itself a conflict and is
   non-terminal or `:accepted` (adoption is not proof), not only claims. Conflicts are engine-derived, never proposed,
   and are closed by resolving a participant rather than by deletion.

   Pairs are canonicalised into sorted id order and de-duplicated, so the
   conflict a pair derives does not depend on which side happens to hold the
   `contradicts` edge, and a symmetric pair that points both ways still
   derives exactly one conflict.

   A conflict is a relation between two objects, so an object contradicting
   its own id derives nothing. Admitting it would mint a `conflict-x-x` that
   no transaction can resolve — nothing supersedes a participant without
   superseding the other — and the §6.1 conflict tier would keep selecting
   it for as long as the run lasts."
  [workspace version]
  (let [objects (get workspace :workspace/objects {})
        contradictable? (fn [id]
                          (when-let [o (get objects id)]
                            (and (not= :conflict (:object/type o))
                                 (or (= :accepted (:object/status o))
                                     (not (object/terminal? o))))))
        pairs (->> (for [[id o] objects
                         target (object/linked o :contradicts)
                         :when (and (not= id target)
                                    (contradictable? id)
                                    (contradictable? target))]
                     (vec (sort [id target])))
                   distinct
                   sort)
        active-ids (into #{} (map conflict-id-of) pairs)]
    (reduce (fn [ws [id target]]
              (let [conflict-id (conflict-id-of [id target])]
                (if (get-in ws [:workspace/objects conflict-id])
                  ws
                  (assoc-in ws [:workspace/objects conflict-id]
                            (object/new-object
                             {:id conflict-id :type :conflict
                              :statement (str "contradiction between " id " and " target)
                              :role :engine :activation "derived" :version version
                              :links {:contradicts #{id target}}})))))
            (resolve-conflicts workspace active-ids version)
            pairs)))
