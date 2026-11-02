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
(ns ai.miniforge.deliberation-harness.experiment
  "One development comparison cell (N15 §0.5): a task, a condition, an
   allowance tier and a replicate run to completion under a fixed schedule.
   The ablated condition gives each participant a private board, so peer
   state cannot leak through scheduling, versions, feedback or lineage.
   The final turn is the metered closing turn inside the allowance."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [ai.miniforge.deliberation-harness.turn :as turn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} complete?
  "True when the cell spent exactly its allowance within its ceilings and
   closed on a valid, reasoned record."
  [state tier started]
  (and (= (:turn state) (:activations tier))
       (<= (:cost state) (:max-normalized-usd tier))
       (< (turn/elapsed started) (:wall-seconds tier))
       (not (get-in state [:payload :invalid?]))
       (not (str/blank? (get-in state [:payload :rationale])))))

(defn- ^{:stratum 0} advance
  "The cell state after a recorded turn."
  [state manifest board-id next-board response participant]
  (let [payload (:payload response)
        spend (turn/normalized-cost (:usage response) (:price-table manifest))]
    (-> state
        (update :turn inc)
        (assoc-in [:boards board-id] next-board)
        (update :history conj (select-keys payload [:solution :rationale]))
        (update :usage #(merge-with + % (:usage response)))
        (update :cost + spend)
        (assoc :payload payload
               :failure (:failure response)
               :usage-unknown? (:usage-unknown? response))
        (update :audit conj {:turn (:turn state) :participant participant}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} cell-result
  "The scored record of a finished cell. Anything short of a complete,
   in-budget closing turn scores zero success."
  [manifest task condition tier replicate state started]
  (let [scored (fixtures/score task (get-in state [:payload :solution]) (:inspected state))
        status (cond (:failure state) :failed
                     (complete? state tier started) :completed
                     :else :budget-boundary)]
    {:task (:id task) :condition condition :tier (:id tier) :replicate replicate
     :manifest-hash (codec/digest manifest)
     :activations (:turn state)
     :usage (:usage state)
     :usage-unknown? (boolean (:usage-unknown? state))
     :failure (:failure state)
     :normalized-usd (:cost state)
     :wall-seconds (turn/elapsed started)
     :status status
     :score (if (= :completed status) scored (assoc scored :success 0))
     :decision-record (select-keys (:payload state) [:solution :rationale])
     :boards (:boards state)}))

(defn- ^{:stratum 1} take-turn
  "Run one turn: record its start, call the model, apply its proposal and
   observations, and record the activation. The closing turn is credited
   with exactly the observations its board showed it. Returns the next
   state, or the journal's anomaly when a record cannot be made durable."
  [{:keys [manifest task condition tier replicate call record!]} state]
  (let [final? (= (:turn state) (dec (:activations tier)))
        participant (turn/actor condition (:turn state) final?)
        board-id (turn/board-key condition participant)
        workspace (get-in state [:boards board-id] (turn/initial-workspace task))
        request (turn/turn-request task condition state participant final? workspace)
        turn-id {:task (:id task) :condition condition :tier (:id tier)
                  :replicate replicate :turn (:turn state) :participant participant}
        started (record! (assoc turn-id :kind :activation-started
                                :request-hash (codec/digest request)))]
    (if (anomaly/anomaly? started)
      started
      (let [response (turn/respond manifest call request)
            next-board (turn/apply-response workspace task participant final? (:payload response))
            recorded (record! (assoc turn-id :kind :activation :request request
                                     :response response :workspace next-board))]
        (if (anomaly/anomaly? recorded)
          recorded
          (cond-> (advance state manifest board-id next-board response participant)
            final? (assoc :inspected (turn/observed-experiments task workspace))))))))

(defn- ^{:stratum 1} finished?
  "True when the allowance is spent, a ceiling is reached, or a turn failed.
   A failure is never retried."
  [state tier started]
  (or (some? (:failure state))
      (>= (:turn state) (:activations tier))
      (>= (:cost state) (:max-normalized-usd tier))
      (>= (turn/elapsed started) (:wall-seconds tier))))

(defn ^{:stratum 1} run-cell
  "Run one cell. `call` receives only the public request and returns
   `{:payload :usage}` or an anomaly; `record!` persists a journal record
   before the next call and returns its hash or an anomaly. Returns the
   cell result after recording it, or the journal anomaly that stopped it."
  [manifest task condition tier replicate call record!]
  (let [started (System/nanoTime)
        cell {:manifest manifest :task task :condition condition :tier tier
              :replicate replicate :call call :record! record!}]
    (loop [state {:turn 0 :boards {} :history [] :inspected #{} :usage turn/empty-usage
                  :cost 0.0 :payload nil :audit []}]
      (cond
        (anomaly/anomaly? state) state
        (finished? state tier started)
        (let [result (cell-result manifest task condition tier replicate state started)
              recorded (record! {:kind :cell-completed :result (dissoc result :boards)})]
          (if (anomaly/anomaly? recorded) recorded result))
        :else (recur (take-turn cell state))))))

