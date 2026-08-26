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

(defn- ^{:stratum 0} apply-links [workspace operation]
  (reduce (fn [ws [edge targets]]
            (reduce (fn [w id]
                      (update-in w [:workspace/objects id]
                                 object/add-link edge (:source operation)))
                    ws
                    targets))
          workspace
          (get operation :links {})))

(defn- ^{:stratum 0} record-challenge [workspace operation context version]
  (if (= :challenge (:op operation))
    (reduce (fn [ws id]
              (let [challenge-id (str "challenge-" version "-" id)]
                (assoc-in ws [:workspace/challenges challenge-id]
                          {:challenge/id challenge-id
                           :challenge/role (:role context)
                           :challenge/target id
                           :challenge/status :open
                           :challenge/version version})))
            workspace
            (tx/touched-ids operation))
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
   still touched, so the clock stays honest."
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
   with the log, never with wall time."
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
