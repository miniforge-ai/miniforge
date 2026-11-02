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

(def ^{:stratum 0} event-only-responsibilities
  "Responsibilities activated only by deadlock or budget events, never by
   ordinary scheduling (N14 §5.2)."
  #{:meta-watchdog})

(defn ^{:stratum 0} responsibility-of
  "The responsibility the runtime assigned `participant`. A participant
   without an assignment is its own responsibility."
  [workspace participant]
  (get-in workspace [:workspace/participant-roles participant] participant))

(defn- ^{:stratum 0} last-actor
  "The participant that acted most recently."
  [workspace]
  (let [logged (peek (get workspace :workspace/log []))]
    (get workspace :workspace/last-actor
         (or (:tx/participant logged) (:tx/role logged)))))

(defn- ^{:stratum 0} next-after
  "The first of `candidates` following `previous` in `roster` order, wrapping.
   Rotation runs over the full roster, so a participant skipped this time
   never moves the rotation back to the start."
  [roster candidates previous]
  (let [roster (vec roster)
        [before after] (split-at (inc (.indexOf roster previous)) roster)]
    (first (filter (set candidates) (concat after before)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ordinary-participants
  "The population in manifest order, without event-only responsibilities
   and without participants refused since the last commit because their
   read scope withholds the required frame. These are the participants a
   quiescence round covers (N14 §7)."
  [workspace]
  (let [refused (get workspace :workspace/frame-refused #{})]
    (remove #(or (contains? event-only-responsibilities (responsibility-of workspace %))
                 (contains? refused %))
            (get workspace :workspace/roles []))))

(defn- ^{:stratum 1} eligible-participants
  "Ordinary participants whose responsibility subscribes to `event`, in
   manifest order so selection never depends on map iteration order.
   Event-only participants never take structural work (N14 §5.2)."
  [workspace event]
  (let [subscribed (set (get-in workspace [:workspace/eligibility event] #{}))]
    (filter #(contains? subscribed (responsibility-of workspace %))
            (ordinary-participants workspace))))

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

(defn ^{:stratum 2} triggered-activation
  "The highest §6.1 structural tier with waiting work and an eligible
   participant, or nil. Within a tier, eligible participants rotate after
   the last actor so one participant cannot monopolise it."
  [workspace]
  (let [triggers [[:conflict (first (open-conflicts workspace))]
                  [:blocked-goal (first (blocked-goals workspace))]
                  [:stale-question (first (stale-questions workspace))]]
        ;; A trigger only fires when something is waiting AND a participant
        ;; is subscribed to it. An eligibility table that omits a
        ;; subscription must not silently suppress the tiers beneath it.
        fired (fn [[event target]]
                (when-let [participant (and target
                                            (next-after (get workspace :workspace/roles [])
                                                        (eligible-participants workspace event)
                                                        (last-actor workspace)))]
                  {:activation/participant participant
                   :activation/role (responsibility-of workspace participant)
                   :activation/reason event
                   :activation/target (:object/id target)}))]
    (first (keep fired triggers))))

(defn ^{:stratum 2} next-activation
  "Select the next participant to activate, or nil when nothing is eligible.

   Priority is the fixed §6.1 order: open conflicts, then blocked goals,
   then stale open questions, then round-robin. The trigger is returned
   alongside the role so the run loop can log why a role was chosen —
   §6.3 requires every scheduling decision to carry a reason."
  [workspace]
  (or (triggered-activation workspace)
      (when-let [participant (next-after (get workspace :workspace/roles [])
                                         (ordinary-participants workspace)
                                         (last-actor workspace))]
        {:activation/participant participant
         :activation/role (responsibility-of workspace participant)
         :activation/reason :round-robin})))
