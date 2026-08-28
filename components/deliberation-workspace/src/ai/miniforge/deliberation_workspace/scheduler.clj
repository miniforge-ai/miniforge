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
(ns ai.miniforge.deliberation-workspace.scheduler
  "The deterministic v0 scheduler of N14 §6. Closing rules live in the
   sibling `termination` namespace.

   §6.2 forbids this scheduler from consuming model-generated numeric
   estimates — confidence, expected information gain, self-reported
   priority. Priority here is structural and reproducible: the same
   workspace always selects the same role. Learned policies are a benchmark
   axis behind this seam, not a v1 dependency."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-staleness-threshold
  "Committed transactions after which an untouched open question counts as
   stale (N14 §6.1). Manifest-configurable; measured in transactions rather
   than wall time so replay stays deterministic."
  3)

(defn- ^{:stratum 0} open-objects-of-type [workspace object-type]
  (->> (get workspace :workspace/objects {})
       vals
       (filter #(= object-type (:object/type %)))
       (remove object/terminal?)
       (sort-by :object/id)))

(defn- ^{:stratum 0} eligible-roles
  "Roles subscribed to `event`, ordered by the manifest's role sequence so
   selection never depends on map iteration order."
  [workspace event]
  (let [roles (get workspace :workspace/roles [])
        table (get workspace :workspace/eligibility {})]
    (filter (set (get table event #{})) roles)))

(defn- ^{:stratum 0} round-robin-next
  "The role following the one that acted most recently, so no role starves."
  [workspace]
  (let [roles (vec (get workspace :workspace/roles []))
        last-role (:tx/role (peek (get workspace :workspace/log [])))
        position (.indexOf roles last-role)]
    (when (seq roles)
      (nth roles (mod (inc position) (count roles))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} open-conflicts
  "Unresolved conflicts, the scheduler's highest structural priority."
  [workspace]
  (open-objects-of-type workspace :conflict))

(defn ^{:stratum 1} blocked-goals
  "Open goals with a blocker declared against them."
  [workspace]
  (let [blocked (into #{} (mapcat #(get-in % [:object/links :depends-on] #{}))
                      (open-objects-of-type workspace :blocker))]
    (filter #(contains? blocked (:object/id %))
            (open-objects-of-type workspace :goal))))

(defn ^{:stratum 1} stale-questions
  "Open questions untouched for at least the staleness threshold (N14 §6.1)."
  [workspace]
  (let [threshold (get workspace :workspace/staleness-threshold
                       default-staleness-threshold)
        version (get workspace :workspace/version 0)]
    (filter #(>= (- version (:object/touched-at %)) threshold)
            (open-objects-of-type workspace :question))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} next-activation
  "Select the next role to activate, or nil when nothing is eligible.

   Priority is the fixed §6.1 order: open conflicts, then blocked goals,
   then stale open questions, then round-robin. The trigger is returned
   alongside the role so the run loop can log why a role was chosen —
   §6.3 requires every scheduling decision to carry a reason."
  [workspace]
  (let [triggers [[:conflict (first (open-conflicts workspace))]
                  [:blocked-goal (first (blocked-goals workspace))]
                  [:stale-question (first (stale-questions workspace))]]
        ;; A trigger only fires when something is waiting AND a role is
        ;; subscribed to it. An eligibility table that omits a subscription
        ;; must not silently suppress the tiers beneath it, so a trigger with
        ;; no eligible role is skipped rather than ending the search.
        fired (fn [[event target]]
                (when target
                  (when-let [role (first (eligible-roles workspace event))]
                    {:activation/role role
                     :activation/reason event
                     :activation/target (:object/id target)})))]
    (or (first (keep fired triggers))
        (when-let [role (round-robin-next workspace)]
          {:activation/role role :activation/reason :round-robin}))))
