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
  "N14 §2.5: a conflict object is derived for every pair of live claims
   linked `contradicts`. Conflicts are engine-derived, never proposed, and
   are closed by resolving a participant rather than by deletion."
  [workspace version]
  (let [objects (get workspace :workspace/objects {})
        contradictable? (fn [id]
                          (when-let [o (get objects id)]
                            (and (not (object/terminal? o))
                                 (not= :conflict (:object/type o)))))
        pairs (for [[id o] (sort-by key objects)
                    target (sort (object/linked o :contradicts))
                    :when (and (contradictable? id) (contradictable? target))]
                [id target])]
    (reduce (fn [ws [id target]]
              (let [conflict-id (str "conflict-" id "-" target)]
                (if (get-in ws [:workspace/objects conflict-id])
                  ws
                  (assoc-in ws [:workspace/objects conflict-id]
                            (object/new-object
                             {:id conflict-id :type :conflict
                              :statement (str "contradiction between " id " and " target)
                              :role :engine :activation "derived" :version version
                              :links {:contradicts #{id target}}})))))
            workspace
            pairs)))
