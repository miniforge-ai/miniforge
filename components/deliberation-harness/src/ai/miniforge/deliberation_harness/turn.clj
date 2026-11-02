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
(ns ai.miniforge.deliberation-harness.turn
  "One participant turn of a development cell: who acts, the public request
   it sees, the response the adapter returns, and how its proposal and
   observations reach its board."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.codec :as codec]
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [ai.miniforge.deliberation-workspace.interface :as board]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} conditions
  "Development conditions; none is a conforming N15 control."
  #{:single :independent :relay :workspace :workspace-ablated})

(def ^{:stratum 0} participants
  "The fixed peer schedule of the multi-participant conditions."
  [:proposer :skeptic :synthesizer])

(def ^{:stratum 0} goal-id "goal")

(def ^{:stratum 0} candidate-prefix
  "Participant-name prefix of the independent condition's private candidates."
  "candidate-")

(def ^{:stratum 0} model-object-keys
  "Object fields a model sees. Sequence numbers and activation identities
   are audit metadata, never model input."
  [:object/id :object/type :object/status :object/statement :object/links
   :object/attrs :object/contested?])

(def ^{:stratum 0} empty-usage {:input 0 :output 0 :provider-usd 0})

(def ^{:stratum 0} nanos-per-second 1e9)

(def ^{:stratum 0} tokens-per-million 1e6)

(defn ^{:stratum 0} normalized-cost
  "Spend under the manifest's pinned per-million-token weights: an
   accounting convention, not a claim about vendor prices."
  [{:keys [input output]} {:keys [input-per-million output-per-million]}]
  (/ (+ (* input input-per-million) (* output output-per-million)) tokens-per-million))

(defn ^{:stratum 0} initial-workspace [task]
  {:workspace/version 0
   :workspace/log []
   :workspace/roles participants
   :workspace/objects {goal-id (board/new-object {:id goal-id :type :goal
                                                  :statement (:brief task)
                                                  :role :interpreter :activation "seed"
                                                  :version 0})}})

(defn ^{:stratum 0} elapsed [started]
  (/ (- (System/nanoTime) started) nanos-per-second))

(defn ^{:stratum 0} actor
  "Who takes `turn`. The closing turn is always the synthesizer's."
  [condition turn final?]
  (cond
    final? :synthesizer
    (= :single condition) :synthesizer
    (= :independent condition) (keyword (str candidate-prefix turn))
    :else (nth participants (mod turn (count participants)))))

(defn ^{:stratum 0} role-of [participant]
  (if (str/starts-with? (name participant) candidate-prefix) :synthesizer participant))

(defn ^{:stratum 0} board-key
  "Only the shared-workspace condition shares one board."
  [condition participant]
  (if (= :workspace condition) :shared participant))

(defn ^{:stratum 0} prior-for
  "The handoff a participant receives: the relay chain, or the submitted
   candidates at the independent condition's closing selection."
  [condition final? history]
  (if (or (= :relay condition) (and (= :independent condition) final?)) history []))

(defn ^{:stratum 0} failed-response
  [failure message-key]
  {:payload {:operations [] :inspect [] :solution {}
             :rationale (messages/system-t message-key) :invalid? true}
   :usage empty-usage
   :failure failure})

(defn ^{:stratum 0} request-size [request]
  (alength (.getBytes ^String (codec/encode request) "UTF-8")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attach-observation
  "Record one fixture observation as receipt-backed execution evidence. The
   fixture reader is a trusted runtime capability, so it commits directly;
   a model cannot manufacture a receipt by naming an evidence class."
  [workspace task participant id]
  (let [statement (get-in task [:experiments id])
        receipt (str "receipt-" id)
        evidence-id (str "evidence-" id "-" (name participant))]
    (if (or (nil? statement) (get-in workspace [:workspace/objects evidence-id]))
      workspace
      (board/replay-transaction
       (assoc-in workspace [:workspace/receipts receipt]
                 {:source-class :execution :statement statement})
       {:tx/role :verifier :tx/participant participant :tx/activation "fixture-reader"
        :tx/operations [{:op :attach-evidence :targets [goal-id]
                         :creates [{:id evidence-id :type :evidence :statement statement
                                    :attrs {:source-class :execution :receipt receipt}}]}]}))))

(defn ^{:stratum 1} model-view [workspace participant]
  {:objects (mapv #(select-keys % model-object-keys)
                  (:projection/objects (board/project workspace participant {:visibility :full})))})

(defn ^{:stratum 1} respond
  "The model's response to `request`, or a failed response: an oversized
   request is never sent, and an adapter anomaly is never retried."
  [manifest call request]
  (if (> (request-size request) (:max-input-bytes manifest))
    (failed-response :context-overflow :lab/context-overflow)
    (let [response (call request)]
      (if (anomaly/anomaly? response)
        (assoc (failed-response (or (anomaly/subtype response) :adapter-failure) :lab/adapter-failed)
               :usage-unknown? true)
        response))))

(defn ^{:stratum 1} commit-turn
  "Admit the payload's operations as `participant`'s turn on its board."
  [workspace participant final? payload]
  (let [proposal (when (seq (:operations payload)) {:tx/operations (:operations payload)})]
    (board/activate-participant
     (assoc-in workspace [:workspace/participant-roles participant] (role-of participant))
     (constantly proposal)
     {:participant participant :reason (if final? :closure :fixed-schedule)})))

(defn ^{:stratum 1} apply-response
  "`workspace` after the participant's proposal and its inspected
   observations."
  [workspace task participant final? payload]
  (reduce #(attach-observation %1 task participant %2)
          (commit-turn workspace participant final? payload)
          (:inspect payload)))

(defn ^{:stratum 1} turn-request
  "The public request for `participant`'s turn: the task's public view, its
   board's model view, its own feedback and its condition's handoff."
  [task condition state participant final? workspace]
  (let [prior-turns (count (filter #(= participant (:participant %)) (:audit state)))]
    {:task (fixtures/public-task task)
     :role (role-of participant)
     :closing? final?
     :id-prefix (str (name participant) "-" (inc prior-turns))
     :available-experiments (vec (sort (keys (:experiments task))))
     :context (model-view workspace participant)
     :feedback (get-in workspace [:workspace/feedback participant])
     :prior (prior-for condition final? (:history state))}))
