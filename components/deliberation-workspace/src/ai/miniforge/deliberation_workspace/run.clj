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
(ns ai.miniforge.deliberation-workspace.run
  "The N14 run loop: schedule, project, activate, admit, repeat until a §7
   closing rule fires.

   The activation function is injected. Every LLM call in a real run happens
   behind it, so the whole loop is exercised deterministically in tests with
   a function that returns canned proposals. It receives a request — the
   participant's projection and the runtime's identity for it — and never the
   workspace (N14 §0.5, §4.1)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.admission :as admission]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.projection :as projection]
   [ai.miniforge.deliberation-workspace.scheduler :as scheduler]
   [ai.miniforge.deliberation-workspace.termination :as termination]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-max-steps
  "Harness backstop on loop iterations. Budgets and closing rules should end
   a run first; a run that reaches this bound is a defect worth surfacing."
  1000)

(defn- ^{:stratum 0} record-event [workspace event]
  (update workspace :workspace/events (fnil conj []) event))

(defn- ^{:stratum 0} activation-id [workspace]
  (str "activation-" (inc (get-in workspace [:workspace/spent :activations] 0))))

(defn- ^{:stratum 0} render
  "The projection `participant` receives: the run's visibility, the
   runtime's read scope for it, and the delta since its last activation."
  [workspace participant]
  (projection/project workspace participant
                      {:visibility (get workspace :workspace/visibility :full)
                       :readable-ids (get-in workspace [:workspace/readable participant])
                       :since (get-in workspace [:workspace/last-seen participant] 0)}))

(defn- ^{:stratum 0} spend
  "Charge one activation to `participant`. A rejected or empty activation
   still ran, so it still costs budget."
  [workspace participant]
  (-> workspace
      (update-in [:workspace/spent :activations] (fnil inc 0))
      (assoc :workspace/last-actor participant)
      (assoc-in [:workspace/last-seen participant] (:workspace/version workspace))))

(defn- ^{:stratum 0} note-quiet
  "Record that `participant` contributed nothing this turn (N14 §7)."
  [workspace participant]
  (update workspace :workspace/passed (fnil conj #{}) participant))

(defn- ^{:stratum 0} allowance-remains?
  "True while the activation budget, when one is set, has a turn left."
  [workspace]
  (let [budget (get-in workspace [:workspace/budget :activations])]
    (or (nil? budget)
        (< (get-in workspace [:workspace/spent :activations] 0) budget))))

(defn- ^{:stratum 0} frame-ids
  "Ids of the task frame every participant must receive: goals and `:hard`
   constraints (N14 §4.2)."
  [workspace]
  (->> (vals (get workspace :workspace/objects {}))
       (filter #(or (= :goal (:object/type %)) (object/hard-constraint? %)))
       (map :object/id)
       set))

(defn- ^{:stratum 0} withheld-frame
  "Frame ids `projection` does not deliver. Non-empty when the runtime read
   scope hides a required goal or constraint: the turn must be refused
   rather than run on a partial frame (N14 §4)."
  [workspace projection]
  (remove (set (map :object/id (:projection/objects projection))) (frame-ids workspace)))

(defn- ^{:stratum 0} closer
  "The first participant holding synthesizer responsibility, or nil."
  [workspace]
  (first (filter #(= :synthesizer (scheduler/responsibility-of workspace %))
                 (get workspace :workspace/roles []))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} settle
  "Fold `proposed` into `spent` through admission, logging the outcome."
  [spent proposed participant context]
  (let [result (admission/admit spent proposed context)]
    (if (anomaly/anomaly? result)
      (-> spent
          (note-quiet participant)
          (assoc-in [:workspace/feedback participant]
                    {:status :rejected :reason (anomaly/subtype result)})
          (record-event {:event :transaction/rejected :role participant
                         :reason (anomaly/subtype result)}))
      (-> (:workspace result)
          (assoc :workspace/passed #{})
          (assoc-in [:workspace/feedback participant] {:status :committed})
          (record-event {:event :transaction/committed :role participant})))))

(defn- ^{:stratum 1} run-turn
  "Invoke the model for `participant` on `projection` and settle what it
   proposes. The turn is charged whether it commits, passes or is rejected."
  [workspace activate {:keys [participant reason target]} projection]
  (let [role (scheduler/responsibility-of workspace participant)
        context {:role role :participant participant
                 :activation (activation-id workspace)
                 :basis (get workspace :workspace/version 0)}
        proposed (activate (assoc context
                                  :projection projection
                                  :closing? (= :closure reason)
                                  :feedback (get-in workspace [:workspace/feedback participant])))
        spent (-> (spend workspace participant)
                  (record-event {:event :activation/completed :role participant
                                 :reason reason :target target}))]
    (if (nil? proposed)
      (-> spent
          (note-quiet participant)
          (record-event {:event :transaction/passed :role participant}))
      (settle spent proposed participant context))))

(defn- ^{:stratum 1} refuse-turn
  "Record a turn refused before any model call because the participant's
   read scope withholds part of the required frame. Nothing ran, so nothing
   is charged; the participant counts as quiet and the rotation moves on."
  [workspace participant withheld]
  (-> workspace
      (assoc :workspace/last-actor participant)
      (note-quiet participant)
      (record-event {:event :activation/refused :role participant
                     :withheld (vec (sort withheld))})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} activate-participant
  "Run one work turn for `participant`. Identity, activation and basis come
   from the runtime; the model's output is only a proposal. `activation` is
   `{:participant :reason :target}`; a closure turn uses the same path. A
   participant whose read scope withholds a goal or `:hard` constraint is
   refused before the model runs (N14 §4)."
  [workspace activate {:keys [participant] :as activation}]
  (let [projection (render workspace participant)
        withheld (seq (withheld-frame workspace projection))]
    (if withheld
      (refuse-turn workspace participant withheld)
      (run-turn workspace activate activation projection))))

(defn ^{:stratum 2} step
  "Run the next scheduled activation, or record that none is eligible.
   Nothing eligible charges nothing: the deadlock rule closes the run."
  [workspace activate]
  (if-let [{:activation/keys [participant reason target]} (scheduler/next-activation workspace)]
    (activate-participant workspace activate
                          {:participant participant :reason reason :target target})
    (record-event workspace {:event :activation/none-eligible})))

(defn ^{:stratum 2} close
  "Attach `closing` to `workspace`. A budget boundary runs one closure turn
   for the synthesizer when the manifest reserved one (N14 §6.3, §7.2);
   without a reserve or a synthesizer the closure is recorded unfunded."
  [workspace activate closing]
  (let [participant (closer workspace)
        funded? (and (:termination/forced-synthesis closing)
                     (pos? (get workspace :workspace/closure-reserve 0))
                     (allowance-remains? workspace)
                     (some? participant))
        closed (if funded?
                 (activate-participant workspace activate
                                       {:participant participant :reason :closure})
                 workspace)]
    (assoc closed :workspace/termination
           (cond-> closing
             (:termination/forced-synthesis closing)
             (assoc :termination/closure (if funded? :executed :unfunded))))))

(defn ^{:stratum 2} run
  "Drive the loop until a §7 closing rule fires, then return the closed
   workspace with its termination record attached. `max-steps` is a harness
   backstop, not a spec rule."
  [workspace activate {:keys [max-steps] :or {max-steps default-max-steps}}]
  (loop [ws workspace
         steps 0]
    (if-let [closing (termination/closing-rule ws)]
      (close ws activate closing)
      (if (>= steps max-steps)
        (assoc ws :workspace/termination {:termination/rule :step-bound-exceeded})
        (recur (step ws activate) (inc steps))))))
