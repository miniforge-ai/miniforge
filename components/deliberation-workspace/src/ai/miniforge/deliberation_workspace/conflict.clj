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

(defn- ^{:stratum 0} contradictable?
  "True when `o` takes part in a contradiction: any object but a conflict
   that is still live, counting an `:accepted` one, since adoption is not
   proof (N14 §2.5)."
  [o]
  (and o
       (not= :conflict (:object/type o))
       (or (= :accepted (:object/status o))
           (not (object/terminal? o)))))

(defn- ^{:stratum 0} conflict-id [[id target]]
  (str "conflict-" id "-" target))

(defn- ^{:stratum 0} resolved-now?
  "True when `o` is an open conflict whose pair is no longer live: a
   transaction resolved or superseded a participant. Every status that ends
   a participant's liveness is terminal, so a resolved conflict never
   reopens."
  [active-ids o]
  (and (= :conflict (:object/type o))
       (= :open (:object/status o))
       (not (contains? active-ids (:object/id o)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} contradiction-pairs
  "Every live pair linked `contradicts`, as sorted `[id target]`, distinct
   and in sorted order. An object contradicting its own id derives nothing:
   no transaction could resolve that conflict."
  [objects]
  (->> (for [[id o] objects
             target (object/linked o :contradicts)
             :when (and (not= id target)
                        (contradictable? o)
                        (contradictable? (get objects target)))]
         (vec (sort [id target])))
       distinct
       sort))

(defn- ^{:stratum 1} resolve-conflicts
  "Resolve each open conflict whose pair is no longer live. Resolved
   conflicts stay in the graph as history."
  [workspace active-ids version]
  (reduce-kv (fn [ws id o]
               (if (resolved-now? active-ids o)
                 (assoc-in ws [:workspace/objects id]
                           (-> o (assoc :object/status :resolved) (object/touch version)))
                 ws))
             workspace
             (get workspace :workspace/objects {})))

(defn- ^{:stratum 1} add-conflict [workspace [id target :as pair] version]
  (let [cid (conflict-id pair)]
    (if (get-in workspace [:workspace/objects cid])
      workspace
      (assoc-in workspace [:workspace/objects cid]
                (object/new-object
                 {:id cid :type :conflict
                  :statement (str "contradiction between " id " and " target)
                  :role :engine :activation "derived" :version version
                  :links {:contradicts #{id target}}})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} derive-conflicts
  "N14 §2.5: derive one conflict per live contradicting pair, canonicalised
   so the side holding the edge does not matter. A conflict is never
   deleted; it is resolved once a participant is resolved or superseded."
  [workspace version]
  (let [pairs (contradiction-pairs (get workspace :workspace/objects {}))
        active-ids (into #{} (map conflict-id) pairs)]
    (reduce #(add-conflict %1 %2 version)
            (resolve-conflicts workspace active-ids version)
            pairs)))
