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
   [ai.miniforge.deliberation-workspace.messages :as messages]
   [ai.miniforge.deliberation-workspace.projection :as projection]
   [ai.miniforge.deliberation-workspace.scheduler :as scheduler]
   [ai.miniforge.deliberation-workspace.termination :as termination]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [ai.miniforge.deliberation-workspace.validation :as validation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-max-steps
  "Harness backstop on loop iterations. Budgets and closing rules should end
   a run first; a run that reaches this bound is a defect worth surfacing."
  1000)

(defn- ^{:stratum 0} record-event [workspace event]
  (update workspace :workspace/events (fnil conj []) event))

(defn- ^{:stratum 0} activation-id [workspace]
  (str "activation-" (inc (get-in workspace [:workspace/spent :activations] 0))))

;; The projection `participant` receives: the run's visibility, the
;; runtime's read scope for it, and the delta since its last activation.
(defn- ^{:stratum 0} render
  [workspace participant]
  (projection/project workspace participant
                      {:visibility (get workspace :workspace/visibility :full)
                       :readable-ids (get-in workspace [:workspace/readable participant])
                       :since (get-in workspace [:workspace/last-seen participant] 0)}))

;; Charge one activation to `participant`. A rejected or empty activation
;; still ran, so it still costs budget.
(defn- ^{:stratum 0} spend
  [workspace participant]
  (-> workspace
      (update-in [:workspace/spent :activations] (fnil inc 0))
      (assoc :workspace/last-actor participant)
      (assoc-in [:workspace/last-seen participant] (:workspace/version workspace))))

;; Record that `participant` contributed nothing this turn (N14 §7).
(defn- ^{:stratum 0} note-quiet
  [workspace participant]
  (update workspace :workspace/passed (fnil conj #{}) participant))

;; True while every configured run-level ceiling (activations, cost) has
;; room left; a dimension with no ceiling never blocks.
;; Pass and frame-refusal markers hold only for the version they were recorded
;; at: any commit, through this loop or straight through admission, starts a
;; new round, so a stale marker can neither close the run nor exclude anyone.
(defn- ^{:stratum 0} current-round [workspace]
  (if (= (:workspace/round-version workspace) (:workspace/version workspace))
    workspace
    (assoc workspace :workspace/passed #{} :workspace/frame-refused #{}
           :workspace/round-version (:workspace/version workspace))))

(defn- ^{:stratum 0} allowance-remains?
  [workspace]
  (every? (fn [dimension]
            (let [ceiling (get-in workspace [:workspace/budget dimension])]
              (or (nil? ceiling)
                  (< (get-in workspace [:workspace/spent dimension] 0) ceiling))))
          [:activations :cost]))

;; True when `participant` sees every object: full visibility and no read
;; scope. Only then is a rejection's reason fed back; otherwise it could reveal
;; whether a hidden object exists, so the participant gets the status alone.
(defn- ^{:stratum 0} open-view? [workspace participant]
  (and (= :full (get workspace :workspace/visibility :full))
       (nil? (get-in workspace [:workspace/readable participant]))))

;; The first participant holding synthesizer responsibility, or nil.
(defn- ^{:stratum 0} closer
  [workspace]
  (first (filter #(= :synthesizer (scheduler/responsibility-of workspace %))
                 (get workspace :workspace/roles []))))

;; How the closure turn ended: never attempted, refused before the model
;; ran (nothing was charged), or executed.
(defn- ^{:stratum 0} closure-status
  [before after funded?]
  (cond
    (not funded?) :unfunded
    (= (get-in before [:workspace/spent :activations])
       (get-in after [:workspace/spent :activations])) :refused
    :else :executed))

;; Whether an admitted `transaction` names an id the participant was neither
;; shown in `projection` nor creates itself. Admission checks ids against the
;; whole graph; acting on a hidden object would confirm it exists (N14 §4.4).
(defn- ^{:stratum 0} unseen-reference? [transaction projection]
  (let [ops (:tx/operations transaction)
        specs (mapcat validation/created-specs ops)
        seen (into (set (map :id specs)) (map :object/id) (:projection/objects projection))]
    (some #(not (contains? seen %))
          (concat (mapcat (fn [op] (mapcat #(get op % []) tx/id-fields)) ops)
                  (mapcat #(mapcat val (get % :links {})) (concat ops specs))))))

;------------------------------------------------------------------------------ Layer 1

;; Fold `proposed` into `spent` through admission, logging the outcome. A
;; reference to an unseen id is refused exactly as an unknown target is.
(defn- ^{:stratum 1} settle
  [spent proposed participant context projection]
  (let [admitted (admission/admit spent proposed context)
        result (if (and (not (anomaly/anomaly? admitted))
                        (unseen-reference? (:transaction admitted) projection))
                 (validation/reject :not-found :anomalies.deliberation/unknown-target
                                    (messages/system-t :anomaly/unknown-target) {})
                 admitted)]
    (if (anomaly/anomaly? result)
      (-> spent
          (note-quiet participant)
          (assoc-in [:workspace/feedback participant]
                    (cond-> {:status :rejected}
                      (open-view? spent participant) (assoc :reason (anomaly/subtype result))))
          (record-event {:event :transaction/rejected :role participant
                         :reason (anomaly/subtype result)}))
      (-> (current-round (:workspace result))
          (assoc-in [:workspace/feedback participant] {:status :committed})
          (record-event {:event :transaction/committed :role participant})))))

;; Invoke the model for `participant` on `projection` and settle what it
;; proposes. The turn is charged whether it commits, passes or is rejected.
(defn- ^{:stratum 1} run-turn
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
      (settle spent proposed participant context projection))))

;; Record a turn refused before any model call because the participant's
;; read scope withholds part of the required frame. Nothing ran, so nothing
;; is charged; the participant counts as quiet, and the scheduler passes it
;; over until the next commit changes the graph its frame is drawn from.
(defn- ^{:stratum 1} refuse-turn
  [workspace participant withheld]
  (-> workspace
      (assoc :workspace/last-actor participant)
      (update :workspace/frame-refused (fnil conj #{}) participant)
      (note-quiet participant)
      (record-event {:event :activation/refused :role participant
                     :withheld (vec (sort withheld))})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} activate-participant
  "One work turn for `participant` (`activation`: `{:participant :reason :target}`).
   An ordinary turn past the closure reserve, or a `:closure` turn with no
   allowance left, is `:activation/unfunded`; one whose read scope withholds a
   goal or `:hard` constraint is refused (N14 §4). Neither runs the model."
  [workspace activate {:keys [participant reason] :as activation}]
  (let [ws (current-round workspace)
        projection (render ws participant)
        withheld (seq (:projection/withheld-frame projection))
        funded? (if (= :closure reason)
                  (allowance-remains? ws)
                  (nil? (termination/exhausted-budget ws)))]
    (cond
      (not funded?) (record-event ws {:event :activation/unfunded :role participant :reason reason})
      withheld (refuse-turn ws participant withheld)
      :else (run-turn ws activate activation projection))))

(defn ^{:stratum 2} step
  "Run the next scheduled activation, or record, uncharged, that none is eligible."
  [workspace activate]
  (if-let [{:activation/keys [participant reason target]} (scheduler/next-activation workspace)]
    (activate-participant workspace activate
                          {:participant participant :reason reason :target target})
    (record-event workspace {:event :activation/none-eligible})))

(defn ^{:stratum 2} close
  "Attach `closing`. A budget boundary runs the reserved synthesizer closure turn
   (N14 §6.3, §7.2), recorded `:executed`, `:refused` or `:unfunded`."
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
             (assoc :termination/closure (closure-status workspace closed funded?))))))

(defn- ^{:stratum 2} drive [workspace activate max-steps]
  (loop [ws (current-round workspace)
         steps 0]
    (if-let [closing (termination/closing-rule ws)]
      (close ws activate closing)
      (if (>= steps max-steps)
        (assoc ws :workspace/termination {:termination/rule :step-bound-exceeded})
        (recur (step ws activate) (inc steps))))))

(defn ^{:stratum 2} run
  "Drive the loop to a §7 closing rule; a closed workspace is returned unchanged,
   and a closure reserve that is not a nat-int is an `:invalid-input` anomaly."
  [workspace activate {:keys [max-steps] :or {max-steps default-max-steps}}]
  (let [reserve (get workspace :workspace/closure-reserve 0)]
    (cond
      (contains? workspace :workspace/termination) workspace
      (nat-int? reserve) (drive workspace activate max-steps)
      :else (anomaly/anomaly :invalid-input
                             (messages/system-t :anomaly/invalid-closure-reserve)
                             {:closure-reserve reserve}))))
