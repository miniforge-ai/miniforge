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

(def ^{:stratum 0} goal-id
  "Object id of the seeded task goal every board starts with; observations
   attach to it."
  "goal")

(def ^{:stratum 0} fixture-reader
  "The trusted runtime participant that records fixture observations. It
   holds the verifier responsibility, which alone may attach execution
   evidence (N14 §5.3); no model participant acts under it."
  :fixture-reader)

(def ^{:stratum 0} candidate-prefix
  "Participant-name prefix of the independent condition's private candidates."
  "candidate-")

(def ^{:stratum 0} model-object-keys
  "Object fields a model sees, including a question's answer and a
   superseded record's replacement. Sequence numbers and activation
   identities are audit metadata, never model input."
  [:object/id :object/type :object/status :object/statement :object/links
   :object/attrs :object/contested? :object/answer :object/superseded-by])

(def ^{:stratum 0} empty-usage
  "Usage of a turn that spent nothing: the identity for summing usage."
  {:input 0 :output 0 :provider-usd 0})

(def ^{:stratum 0} nanos-per-second
  "Converts `System/nanoTime` deltas to the seconds of wall ceilings."
  1e9)

(def ^{:stratum 0} tokens-per-million
  "Divisor for the manifest's per-million-token price weights."
  1e6)

(defn ^{:stratum 0} normalized-cost
  "Spend under the manifest's pinned per-million-token weights: an
   accounting convention, not a claim about vendor prices."
  [{:keys [input output]} {:keys [input-per-million output-per-million]}]
  (/ (+ (* input input-per-million) (* output output-per-million)) tokens-per-million))

(defn ^{:stratum 0} experiment-object-id [id] (str "experiment-" id))

(defn ^{:stratum 0} receipt-id [id] (str "receipt-" id))

(defn- ^{:stratum 0} seed [id type statement]
  [id (board/new-object {:id id :type type :statement statement
                         :role :interpreter :activation "seed" :version 0})])

(defn ^{:stratum 0} initial-workspace
  "A board seeded with the task goal and one experiment object per fixture
   observation, which observations attach to."
  [task]
  {:workspace/version 0
   :workspace/log []
   :workspace/roles participants
   :workspace/participant-roles {fixture-reader :verifier}
   :workspace/objects (into {}
                            (cons (seed goal-id :goal (:brief task))
                                  (map #(seed (experiment-object-id %) :experiment %)
                                       (keys (:experiments task)))))})

(defn ^{:stratum 0} observed?
  "True when `workspace` holds execution evidence whose receipt attests
   fixture observation `id` with exactly its statement."
  [workspace id]
  (let [receipt (receipt-id id)
        attested (get-in workspace [:workspace/receipts receipt])]
    (boolean
     (and attested
          (some #(and (= :evidence (:object/type %))
                      (= {:source-class :execution :receipt receipt}
                         (select-keys (:object/attrs %) [:source-class :receipt]))
                      (= (:statement attested) (:object/statement %)))
                (vals (:workspace/objects workspace)))))))

(defn ^{:stratum 0} observed-experiments
  "The fixture observations a participant viewing `workspace` can see."
  [task workspace]
  (set (filter #(observed? workspace %) (keys (:experiments task)))))

(defn- ^{:stratum 0} free-id
  "`base`, or the first `base-n` no object on `workspace` already uses."
  [workspace base]
  (first (remove #(contains? (:workspace/objects workspace) %)
                 (cons base (map #(str base "-" %) (iterate inc 1))))))

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

(defn ^{:stratum 0} well-formed?
  "True when `response` has the shape a cell reads: a payload map with an
   optional sequence of experiment ids, a solution map and a rationale
   string, and usage whose every value is a number."
  [{:keys [payload usage]}]
  (and (map? payload)
       (let [inspect (:inspect payload)]
         (or (nil? inspect) (and (sequential? inspect) (every? string? inspect))))
       (map? (:solution payload))
       (string? (:rationale payload))
       (map? usage)
       (every? number? (vals usage))))

(defn ^{:stratum 0} request-size [request]
  (alength (.getBytes ^String (codec/encode request) "UTF-8")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attach-observation
  "Record fixture observation `id` as receipt-backed execution evidence
   supporting its experiment object. The runtime records the receipt, and
   the fixture reader's attachment passes full admission; a board that
   already shows the observation is unchanged, and so is one admission
   refuses."
  [workspace task id]
  (let [statement (get-in task [:experiments id])
        receipt (receipt-id id)]
    (if (or (nil? statement) (observed? workspace id))
      workspace
      (let [recorded (assoc-in workspace [:workspace/receipts receipt]
                               {:source-class :execution :statement statement})
            evidence-id (free-id recorded (str "evidence-" id))
            admitted (board/admit
                      recorded
                      {:tx/operations [{:op :attach-evidence :targets [(experiment-object-id id)]
                                        :creates [{:id evidence-id :type :evidence :statement statement
                                                   :attrs {:source-class :execution :receipt receipt}}]
                                        :links {:supports [evidence-id]}}]}
                      {:role :verifier :participant fixture-reader
                       :activation (str (name fixture-reader) "-" id)
                       :basis (get recorded :workspace/version 0)})]
        (if (anomaly/anomaly? admitted) workspace (:workspace admitted))))))

(defn ^{:stratum 1} model-view [workspace participant]
  {:objects (mapv #(select-keys % model-object-keys)
                  (:projection/objects (board/project workspace participant {:visibility :full})))})

(defn ^{:stratum 1} respond
  "The model's response to `request`, or a failed response: an oversized
   request is never sent, an adapter anomaly is never retried, and a
   malformed response is a failed activation with unknown usage."
  [manifest call request]
  (if (> (request-size request) (:max-input-bytes manifest))
    (failed-response :context-overflow :lab/context-overflow)
    (let [response (call request)]
      (cond
        (anomaly/anomaly? response)
        (assoc (failed-response (or (anomaly/subtype response) :adapter-failure) :lab/adapter-failed)
               :usage-unknown? true)

        (not (well-formed? response))
        (assoc (failed-response :malformed-response :lab/malformed-response) :usage-unknown? true)

        :else response))))

(defn ^{:stratum 1} commit-turn
  "Admit the payload's operations as `participant`'s turn on its board. No
   operations is a pass; any other shape goes to admission, which rejects
   what it cannot accept."
  [workspace participant final? payload]
  (let [operations (:operations payload)
        pass? (or (nil? operations) (and (sequential? operations) (empty? operations)))
        proposal (when-not pass? {:tx/operations operations})]
    (board/activate-participant
     (assoc-in workspace [:workspace/participant-roles participant] (role-of participant))
     (constantly proposal)
     {:participant participant :reason (if final? :closure :fixed-schedule)})))

(defn ^{:stratum 1} apply-response
  "`workspace` after the participant's proposal and its inspected
   observations."
  [workspace task participant final? payload]
  (reduce #(attach-observation %1 task %2)
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
