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

(defn ^{:stratum 0} derive-conflicts
  "Recompute explicit contradictions, retaining resolved conflict history.
   Adoption is not proof: accepted claims still participate in contradictions."
  [workspace version]
  (let [objects (:workspace/objects workspace)
        live? (fn [id] (let [o (get objects id)]
                         (and o (not= :conflict (:object/type o))
                              (not (contains? #{:rejected :superseded :answered :retired
                                                :resolved :aborted} (:object/status o))))))
        pairs (->> (for [[id o] objects target (object/linked o :contradicts)
                         :when (and (not= id target) (live? id) (live? target))]
                     (vec (sort [id target]))) distinct sort)
        active (set (map (fn [[a b]] (str "conflict-" a "-" b)) pairs))
        marked (reduce-kv
                (fn [ws id o]
                  (if (= :conflict (:object/type o))
                    (let [status (if (contains? active id) :open :resolved)]
                      (if (= status (:object/status o)) ws
                          (assoc-in ws [:workspace/objects id]
                                    (-> o (assoc :object/status status) (object/touch version)))))
                    ws)) workspace objects)]
    (reduce (fn [ws [a b]]
              (let [id (str "conflict-" a "-" b)]
                (if (get-in ws [:workspace/objects id]) ws
                    (assoc-in ws [:workspace/objects id]
                              (object/new-object {:id id :type :conflict
                                                  :statement (str "contradiction between " a " and " b)
                                                  :role :engine :activation "derived" :version version
                                                  :links {:contradicts #{a b}}}))))) marked pairs)))
