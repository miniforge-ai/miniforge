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
(ns ai.miniforge.deliberation-workspace.commit
  "Applying a validated transaction to the workspace (N14 §3) and deriving
   conflicts (§2.5).

   Status effects are derived from the operation, never from agent-supplied
   data: an activation chooses which operation to propose, and the engine
   decides what that operation does. Otherwise a transaction could name
   `:challenge` and carry `:accepted`."
  (:require
   [ai.miniforge.deliberation-workspace.conflict :as conflict]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-known-targets
  "Every id an operation touches must already exist in the workspace.

   `validation/check-targets` refuses a missing target, so one arriving here
   is a broken contract between validation and commit rather than agent
   input — rule 005 territory, and it throws. Degrading instead is what let
   the two writers below disagree: `apply-links` filtered unknown targets
   out while `apply-status` handed them to `update-in`, where `object/touch`
   received nil and `assoc` fabricated a `#:object{:touched-at n}`
   placeholder carrying no type, status, or statement. Both readers of
   `touched-ids` now share this single precondition instead of each holding
   a private opinion about an id that cannot legally be here."
  [workspace operation]
  (let [known (get workspace :workspace/objects {})
        missing (remove #(contains? known %) (tx/touched-ids operation))]
    (when (seq missing)
      (throw (IllegalArgumentException.
              (str "Operation " (pr-str (:op operation))
                   " targets objects absent from the workspace: "
                   (pr-str (vec missing))
                   " — validation must reject these before commit"))))))

(defn- ^{:stratum 0} apply-links
  "Add the operation's edges to the objects it declared as targets.

   Edges are written onto the DECLARED targets and point at the
   destinations in `:links`. Writing onto the destinations instead would
   mutate objects the validator never saw: `touched-ids` reads `:targets`
   only, so target-existence, staleness, and terminal checks would all be
   bypassed for anything reachable through `:links`.

   Targets are written unscreened. `assert-known-targets` has already
   established that each one exists, so filtering here could only differ
   from `apply-status` about a case neither is allowed to see."
  [workspace operation]
  (let [targets (tx/touched-ids operation)]
    (reduce (fn [ws [edge destinations]]
              (reduce (fn [w target-id]
                        (reduce (fn [acc destination]
                                  (update-in acc [:workspace/objects target-id]
                                             object/add-link edge destination))
                                w
                                destinations))
                      ws
                      targets))
            workspace
            (get operation :links {}))))

(defn- ^{:stratum 0} record-challenge
  "Record one open challenge per target for the §3.5 anti-livelock cap.

   The ordinal keeps two challenges on the same target inside one commit
   from colliding. Overwriting would undercount open challenges, which is
   exactly what the cap reads.

   Ordinals are handed out over SORTED targets from a single start count
   read before the reduce. `touched-ids` returns a set, so counting inside
   the reduce would tie each id to set iteration order: the same
   transaction could rebuild different challenge ids on a different run,
   and an id that does not follow from the log is not reconstructible
   from it."
  [workspace operation context version]
  (if (= :challenge (:op operation))
    (let [start (count (get workspace :workspace/challenges {}))]
      (reduce (fn [ws [ordinal id]]
                (let [challenge-id (str "challenge-" version "-"
                                        (+ start ordinal) "-" id)]
                  (assoc-in ws [:workspace/challenges challenge-id]
                            {:challenge/id challenge-id
                             :challenge/role (:role context)
                             :challenge/target id
                             :challenge/status :open
                             :challenge/version version})))
              workspace
              (map-indexed vector (sort (tx/touched-ids operation)))))
    workspace))

(defn- ^{:stratum 0} insert-created
  "Insert the objects `operation` creates, stamping provenance from the
   transaction rather than trusting the activation's own report of it."
  [workspace operation {:keys [role activation version]}]
  (reduce (fn [ws spec]
            (let [created (object/new-object
                           (assoc spec :role role :activation activation
                                  :version version))]
              (assoc-in ws [:workspace/objects (:object/id created)] created)))
          workspace
          (get operation :creates [])))

(defn- ^{:stratum 0} apply-status
  "Impose the operation's status on its targets and advance their staleness
   clock. A status illegal for the target's type is skipped; the object is
   still touched, so the clock stays honest.

   Every target is known to exist by the time this runs — see
   `assert-known-targets` — so `update-in` always lands on a real object
   rather than seeding a placeholder at a missing id."
  [workspace operation version]
  (let [status (if (= :close-goal (:op operation))
                 (tx/goal-outcomes (:outcome operation))
                 (get tx/status-effect (:op operation)))]
    (reduce (fn [ws id]
              (let [current (get-in ws [:workspace/objects id])]
                (if (and status (object/legal-status? (:object/type current) status))
                  (update-in ws [:workspace/objects id]
                             #(-> % (assoc :object/status status)
                                  (object/touch version)))
                  (update-in ws [:workspace/objects id] object/touch version))))
            workspace
            (tx/touched-ids operation))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} apply-operation [workspace operation context]
  (let [version (:version context)]
    (assert-known-targets workspace operation)
    (-> workspace
        (insert-created operation context)
        (apply-status operation version)
        (apply-links operation)
        (record-challenge operation context version))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit
  "Apply a validated transaction and advance the workspace version.

   The version increments once per committed transaction — it is the clock
   every basis check and staleness measure reads, so it must move exactly
   with the log, never with wall time.

   `transaction` must already have passed `validation/validate`: an
   operation touching an object the workspace does not hold throws rather
   than being quietly absorbed."
  [workspace transaction]
  (let [version (inc (get workspace :workspace/version 0))
        context {:role (:tx/role transaction)
                 :activation (:tx/activation transaction)
                 :version version}]
    (-> (reduce #(apply-operation %1 %2 context)
                workspace
                (:tx/operations transaction))
        (conflict/derive-conflicts version)
        (assoc :workspace/version version)
        (update :workspace/log (fnil conj []) transaction))))
