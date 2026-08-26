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
(ns ai.miniforge.deliberation-workspace.projection
  "Deterministic role projections (N14 §4) and the `cross_visibility: none`
   ablation switch (§4.4).

   The projection is an activation's only task-specific input (§4.1). That
   is what makes the ablation a real experiment rather than a plausibility
   argument: if shared state matters, blinding roles to each other must
   change outcomes, because the projection is the only path influence can
   travel."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} visibility-modes
  "Cross-visibility settings. :full is the ordinary workspace; :none is the
   N15 ablation arm (condition C7)."
  #{:full :none})

(defn ^{:stratum 0} visible?
  "True when `role` may see `object` under `visibility`.

   The §4.4 visible set is exactly: goals and hard constraints (the run's
   shared frame, not another role's contribution), the role's own objects,
   the interpreter's specification-derived objects, and anything the user
   injected via OCI. Nothing else — not even an existence signal.

   User-injected objects carry `:object/role :user`, the same principal
   `transaction/permitted?` exempts from the §5.3 role matrix. One canonical
   place records who authored an object; a second marker on `:object/attrs`
   would drift from it."
  [object role visibility]
  (case visibility
    :full true
    :none (or (= :goal (:object/type object))
              (object/hard-constraint? object)
              (= role (:object/role object))
              (contains? #{:interpreter :user} (:object/role object)))))

(defn- ^{:stratum 0} by-id [objects]
  (sort-by :object/id objects))

(defn- ^{:stratum 0} references
  "Object ids `object` points at across all its typed edges."
  [object]
  (reduce into #{} (vals (get object :object/links {}))))

(defn- ^{:stratum 0} of-type [objects object-type]
  (filter #(= object-type (:object/type %)) objects))

(defn- ^{:stratum 0} open? [object]
  (not (object/terminal? object)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} renderable-conflict?
  "A conflict is rendered only when every object it references is visible
   (N14 §4.4). A conflict naming an invisible object would leak that the
   object exists, which is exactly what the ablation forbids."
  [conflict visible-ids]
  (every? visible-ids (references conflict)))

(defn ^{:stratum 1} visible-objects
  "Every object `role` may see, ordered by id so the projection is a
   deterministic function of its inputs (N14 §4.1)."
  [workspace role visibility]
  (by-id (filter #(visible? % role visibility)
                 (vals (get workspace :workspace/objects {})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} project
  "Render the projection `role` sees at the workspace's current version.

   Deterministic: identical (workspace, role, visibility, since) inputs
   produce an identical value. `since` is the workspace version at the
   role's previous activation; the delta carries everything touched after
   it, which is how a role learns what moved without re-reading the graph.

   Required content per §4.2 — goals, hard constraints, open conflicts, and
   the delta — is computed over the visible set, so the ablation cannot be
   defeated through derived objects."
  [workspace role {:keys [visibility since] :or {visibility :full since 0}}]
  (let [candidates (visible-objects workspace role visibility)
        candidate-ids (into #{} (map :object/id) candidates)
        ;; A conflict the role may otherwise see is withheld ENTIRELY when it
        ;; references something hidden — dropping it from :projection/conflicts
        ;; alone would still leak its existence through the object list and the
        ;; delta. One pass suffices: conflicts never reference other conflicts.
        visible (remove #(and (= :conflict (:object/type %))
                              (not (renderable-conflict? % candidate-ids)))
                        candidates)
        conflicts (filter open? (of-type visible :conflict))]
    {:projection/version (get workspace :workspace/version 0)
     :projection/role role
     :projection/visibility visibility
     :projection/goals (vec (of-type visible :goal))
     :projection/hard-constraints (vec (filter object/hard-constraint? visible))
     :projection/conflicts (vec conflicts)
     :projection/delta (vec (filter #(> (:object/touched-at %) since) visible))
     :projection/objects (vec visible)}))
