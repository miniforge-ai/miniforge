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

(ns ai.miniforge.decision.interface-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.decision.interface :as decision]
   [ai.miniforge.decision.spec :as spec]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- valid-control-plane-checkpoint
  "Build a minimal valid control-plane checkpoint for use as a baseline."
  [& {:as overrides}]
  (decision/create-control-plane-checkpoint
   (random-uuid)
   "Should I merge PR #42?"
   (merge {:type :approval
           :priority :high
           :options ["yes" "no"]}
          overrides)))

(defn- valid-approve-response
  [& {:as overrides}]
  (merge {:type :approve
          :value "yes"
          :authority-role :human}
         overrides))

(defn- loop-state
  "Build a loop-state map for create-loop-escalation-checkpoint."
  [& {:as overrides}]
  (merge {:loop/id          (random-uuid)
          :loop/state       :escalated
          :loop/iteration   4
          :loop/errors      [{:message "Missing paren"}]
          :loop/task        {:task/id (random-uuid)
                             :task/type :implement}
          :loop/termination {:reason :max-iterations}}
         overrides))

;------------------------------------------------------------------------------ Layer 1
;; Enum exposure and stability

(deftest enum-lists-are-stable-and-complete-test
  (testing "Each enum list contains the documented values"
    (is (= #{:control-plane-agent :loop-escalation :workflow-node
             :external-agent :policy-gate}
           (set decision/source-kinds)))
    (is (= #{:human :system :model :rule :hybrid}
           (set decision/authority-kinds)))
    (is (= #{:pending :resolved :cancelled :expired}
           (set decision/checkpoint-statuses)))
    (is (= #{:pending :resolved :completed}
           (set decision/episode-statuses)))
    (is (= #{:approve :approve-with-constraints :reject :choose-option
             :request-more-evidence :reroute :mark-delegable
             :always-escalate :defer}
           (set decision/response-types)))
    (is (= #{:low :medium :high :critical}
           (set decision/risk-tiers)))))

(deftest schema-vars-re-exported-test
  (testing "interface re-exports schemas point at the spec namespace"
    (is (= spec/DecisionCheckpoint     decision/DecisionCheckpoint))
    (is (= spec/DecisionEpisode        decision/DecisionEpisode))
    (is (= spec/ControlPlaneContext    decision/ControlPlaneContext))
    (is (= spec/LoopEscalationContext  decision/LoopEscalationContext))
    (is (= spec/DecisionContext        decision/DecisionContext))))

;------------------------------------------------------------------------------ Layer 1
;; Validation helpers (valid? / explain / validate)

(deftest valid?-returns-bool-test
  (testing "valid? returns true for conforming values"
    (let [cp (valid-control-plane-checkpoint)]
      (is (true? (decision/valid? decision/DecisionCheckpoint cp)))))
  (testing "valid? returns false for non-conforming values"
    (is (false? (decision/valid? decision/DecisionCheckpoint {})))
    (is (false? (decision/valid? decision/DecisionCheckpoint {:checkpoint/id "not-a-uuid"})))))

(deftest explain-humanizes-errors-test
  (testing "explain returns nil when the value validates"
    (is (nil? (decision/explain decision/DecisionCheckpoint
                                (valid-control-plane-checkpoint)))))
  (testing "explain returns a non-nil structure when the value fails"
    (let [errs (decision/explain decision/DecisionCheckpoint {})]
      (is (some? errs)))))

(deftest validate-returns-value-or-throws-test
  (testing "validate returns the value when it conforms"
    (let [cp (valid-control-plane-checkpoint)]
      (is (= cp (decision/validate decision/DecisionCheckpoint cp)))))
  (testing "validate throws ex-info carrying :schema, :value, :errors"
    (let [thrown (try
                   (decision/validate decision/DecisionCheckpoint {:bogus 1})
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (let [data (ex-data thrown)]
        (is (contains? data :schema))
        (is (contains? data :value))
        (is (some? (:errors data)))))))

;------------------------------------------------------------------------------ Layer 1
;; create-checkpoint defaults

(deftest create-checkpoint-fills-defaults-test
  (testing "Missing id/status/created-at/requested-authority get sensible defaults"
    (let [cp (decision/create-checkpoint
              {:source   {:kind :control-plane-agent}
               :proposal {:action-type    :request-human-decision
                          :decision-class :approval
                          :summary        "Approve?"}})]
      (is (uuid?  (:checkpoint/id cp)))
      (is (= :pending (:checkpoint/status cp)))
      (is (inst?  (:checkpoint/created-at cp)))
      (is (= :human (:checkpoint/requested-authority cp))))))

(deftest create-checkpoint-respects-explicit-fields-test
  (testing "Explicit checkpoint-id, status, created-at, requested-authority are preserved"
    (let [id  (random-uuid)
          ts  (java.util.Date. 1700000000000)
          cp  (decision/create-checkpoint
               {:checkpoint-id          id
                :status                 :resolved
                :created-at             ts
                :requested-authority    :rule
                :source                 {:kind :policy-gate}
                :proposal               {:action-type    :auto-approve
                                         :decision-class :approval
                                         :summary        "Auto approval"}
                :response               (valid-approve-response :authority-role :rule)})]
      (is (= id (:checkpoint/id cp)))
      (is (= :resolved (:checkpoint/status cp)))
      (is (= ts (:checkpoint/created-at cp)))
      (is (= :rule (:checkpoint/requested-authority cp)))
      (is (some? (:response cp))))))

(deftest create-checkpoint-omits-optional-keys-when-absent-test
  (testing "task/uncertainty/risk/context/response are omitted (not nil) when not supplied"
    (let [cp (decision/create-checkpoint
              {:source   {:kind :control-plane-agent}
               :proposal {:action-type    :request-human-decision
                          :decision-class :approval
                          :summary        "X"}})]
      (is (not (contains? cp :task)))
      (is (not (contains? cp :uncertainty)))
      (is (not (contains? cp :risk)))
      (is (not (contains? cp :context)))
      (is (not (contains? cp :response))))))

;------------------------------------------------------------------------------ Layer 1
;; resolve-checkpoint

(deftest resolve-checkpoint-marks-resolved-and-stamps-time-test
  (testing "resolve-checkpoint sets :checkpoint/status :resolved and adds :resolved-at"
    (let [cp        (valid-control-plane-checkpoint)
          before-ms (System/currentTimeMillis)
          resolved  (decision/resolve-checkpoint cp (valid-approve-response))]
      (is (= :resolved (:checkpoint/status resolved)))
      (is (inst? (:checkpoint/resolved-at resolved)))
      (is (>= (.getTime ^java.util.Date (:checkpoint/resolved-at resolved))
              before-ms)))))

(deftest resolve-checkpoint-attaches-response-test
  (testing "resolve-checkpoint stores the supervision response"
    (let [cp       (valid-control-plane-checkpoint)
          response (valid-approve-response :rationale "looks good")
          resolved (decision/resolve-checkpoint cp response)]
      (is (= response (:response resolved))))))

(deftest resolve-checkpoint-preserves-other-fields-test
  (testing "resolve-checkpoint does not mutate id, source, proposal"
    (let [cp       (valid-control-plane-checkpoint)
          resolved (decision/resolve-checkpoint cp (valid-approve-response))]
      (is (= (:checkpoint/id cp) (:checkpoint/id resolved)))
      (is (= (:source cp)        (:source resolved)))
      (is (= (:proposal cp)      (:proposal resolved))))))

;------------------------------------------------------------------------------ Layer 1
;; decision-response

(deftest decision-response-known-types-test
  (testing "decision-response maps each known decision type to a response type"
    (is (= :approve            (:type (decision/decision-response :approval     "ok"))))
    (is (= :choose-option      (:type (decision/decision-response :choice       "rebase"))))
    (is (= :approve-with-constraints (:type (decision/decision-response :input  "with-tests"))))
    (is (= :approve            (:type (decision/decision-response :confirmation "yes"))))))

(deftest decision-response-unknown-type-defaults-to-approve-test
  (testing "decision-response falls back to :approve for an unknown decision-type"
    (is (= :approve (:type (decision/decision-response :totally-unknown "yes"))))))

(deftest decision-response-attaches-rationale-when-given-test
  (testing "decision-response includes :rationale only when supplied"
    (is (not (contains? (decision/decision-response :approval "yes") :rationale)))
    (let [r (decision/decision-response :approval "yes" "smoke tested")]
      (is (= "smoke tested" (:rationale r))))))

(deftest decision-response-always-tags-authority-role-human-test
  (testing "decision-response defaults :authority-role to :human"
    (is (= :human (:authority-role (decision/decision-response :approval "yes"))))))

;------------------------------------------------------------------------------ Layer 1
;; create-control-plane-checkpoint — option matrix

(deftest cp-checkpoint-omits-alternatives-when-no-options-test
  (testing "No :options ⇒ no :alternatives key in the proposal"
    (let [cp (decision/create-control-plane-checkpoint
              (random-uuid) "Just confirm" {:type :confirmation})]
      (is (not (contains? (:proposal cp) :alternatives))))))

(deftest cp-checkpoint-builds-alternatives-from-options-test
  (testing "Options are normalized into indexed alternatives"
    (let [cp (decision/create-control-plane-checkpoint
              (random-uuid) "Pick one"
              {:type :choice :options ["squash" "rebase" "merge-commit"]})
          alts (get-in cp [:proposal :alternatives])]
      (is (= 3 (count alts)))
      (is (= [0 1 2] (mapv :id alts)))
      (is (= ["squash" "rebase" "merge-commit"] (mapv :summary alts))))))

(deftest cp-checkpoint-priority-maps-to-risk-tier-test
  (testing "Each priority maps to its same-named risk tier; default is :medium"
    (is (= :critical (-> (decision/create-control-plane-checkpoint
                          (random-uuid) "x" {:type :approval :priority :critical})
                         :risk :tier)))
    (is (= :high     (-> (decision/create-control-plane-checkpoint
                          (random-uuid) "x" {:type :approval :priority :high})
                         :risk :tier)))
    (is (= :medium   (-> (decision/create-control-plane-checkpoint
                          (random-uuid) "x" {:type :approval :priority :medium})
                         :risk :tier)))
    (is (= :low      (-> (decision/create-control-plane-checkpoint
                          (random-uuid) "x" {:type :approval :priority :low})
                         :risk :tier)))
    (is (= :medium   (-> (decision/create-control-plane-checkpoint
                          (random-uuid) "x" {:type :approval})
                         :risk :tier)))))

(deftest cp-checkpoint-decision-class-from-type-test
  (testing "Each decision type maps to its decision-class; unknown defaults to :implementation-pattern-choice"
    (is (= :approval                    (-> (decision/create-control-plane-checkpoint
                                             (random-uuid) "x" {:type :approval})
                                            :proposal :decision-class)))
    (is (= :implementation-pattern-choice
                                        (-> (decision/create-control-plane-checkpoint
                                             (random-uuid) "x" {:type :choice})
                                            :proposal :decision-class)))
    (is (= :request-for-input           (-> (decision/create-control-plane-checkpoint
                                             (random-uuid) "x" {:type :input})
                                            :proposal :decision-class)))
    (is (= :confirmation                (-> (decision/create-control-plane-checkpoint
                                             (random-uuid) "x" {:type :confirmation})
                                            :proposal :decision-class)))
    (is (= :implementation-pattern-choice
                                        (-> (decision/create-control-plane-checkpoint
                                             (random-uuid) "x" {:type :weird-type})
                                            :proposal :decision-class)))))

(deftest cp-checkpoint-uncertainty-includes-confidence-when-given-test
  (testing "agent-confidence is attached only when supplied"
    (let [without (decision/create-control-plane-checkpoint
                   (random-uuid) "x" {:type :approval})
          with    (decision/create-control-plane-checkpoint
                   (random-uuid) "x" {:type :approval
                                      :agent-confidence 0.42})]
      (is (= :agent-request (-> without :uncertainty :class)))
      (is (not (contains? (:uncertainty without) :agent-confidence)))
      (is (= 0.42 (-> with :uncertainty :agent-confidence))))))

(deftest cp-checkpoint-context-conditional-keys-test
  (testing "Context omits unset keys but populates supplied ones"
    (let [empty-ctx (-> (decision/create-control-plane-checkpoint
                         (random-uuid) "x" {:type :approval})
                        :context)
          deadline  (java.util.Date. 1700000000000)
          full-ctx  (-> (decision/create-control-plane-checkpoint
                         (random-uuid) "x"
                         {:type :approval
                          :context "Full context text"
                          :deadline deadline
                          :tags     [:urgent :prod]})
                        :context)]
      (is (= {} empty-ctx))
      (is (= "Full context text" (:context/text     full-ctx)))
      (is (= deadline             (:context/deadline full-ctx)))
      (is (= #{:urgent :prod}    (:context/tags    full-ctx))))))

;------------------------------------------------------------------------------ Layer 1
;; create-loop-escalation-checkpoint

(deftest loop-escalation-default-summary-test
  (testing "Without :summary opt, the escalation summary is auto-generated from
            the loop iteration count and task type"
    (let [cp (decision/create-loop-escalation-checkpoint (loop-state) {})
          summary (-> cp :proposal :summary)]
      (is (re-find #"Loop escalation after 4 attempt"          summary))
      (is (re-find #"implement"                                 summary)))))

(deftest loop-escalation-explicit-summary-overrides-default-test
  (testing "Explicit :summary opt is preserved verbatim"
    (let [cp (decision/create-loop-escalation-checkpoint
              (loop-state)
              {:summary "Custom summary text"})]
      (is (= "Custom summary text" (-> cp :proposal :summary))))))

(deftest loop-escalation-task-includes-task-id-test
  (testing "Task block carries kind :loop-escalation and the task-id"
    (let [task-id (random-uuid)
          cp (decision/create-loop-escalation-checkpoint
              (loop-state :loop/task {:task/id task-id :task/type :review})
              {})]
      (is (= :loop-escalation (-> cp :task :kind)))
      (is (= task-id          (-> cp :task :task-id))))))

(deftest loop-escalation-task-without-id-omits-key-test
  (testing "Loop with no task-id produces a task block without :task-id"
    (let [cp (decision/create-loop-escalation-checkpoint
              (loop-state :loop/task {:task/type :implement})
              {})]
      (is (= :loop-escalation (-> cp :task :kind)))
      (is (not (contains? (:task cp) :task-id))))))

(deftest loop-escalation-includes-artifact-files-and-diff-test
  (testing "Artifact path becomes :files; content (truncated to 160 chars) becomes :diff-summary"
    (let [content (apply str (repeat 200 \a))
          cp (decision/create-loop-escalation-checkpoint
              (loop-state :loop/artifact {:artifact/path    "src/foo.clj"
                                          :artifact/content content})
              {})
          proposal (:proposal cp)]
      (is (= ["src/foo.clj"] (:files proposal)))
      (is (= 160 (count (:diff-summary proposal)))))))

(deftest loop-escalation-no-artifact-omits-files-and-diff-test
  (testing "Without an artifact, :files and :diff-summary are absent"
    (let [cp (decision/create-loop-escalation-checkpoint (loop-state) {})
          proposal (:proposal cp)]
      (is (not (contains? proposal :files)))
      (is (not (contains? proposal :diff-summary))))))

(deftest loop-escalation-uncertainty-summarizes-errors-test
  (testing "Uncertainty reason is the joined first two error messages, taken from
            either :anomaly/message or :message"
    (let [errors [{:message "first"}
                  {:anomaly {:anomaly/message "second"}}
                  {:message "third"}]
          cp (decision/create-loop-escalation-checkpoint
              (loop-state :loop/errors errors)
              {})]
      (is (= :validation-failure (-> cp :uncertainty :class)))
      (is (= "first; second" (-> cp :uncertainty :reason))))))

(deftest loop-escalation-uncertainty-empty-errors-fallback-test
  (testing "With no parseable errors, the reason falls back to a default string"
    (let [cp (decision/create-loop-escalation-checkpoint
              (loop-state :loop/errors [])
              {})]
      (is (= "Loop exhausted repair budget without convergence."
             (-> cp :uncertainty :reason))))))

(deftest loop-escalation-risk-tier-default-and-override-test
  (testing "Default risk tier is :medium; overridable via opts"
    (is (= :medium   (-> (decision/create-loop-escalation-checkpoint (loop-state) {})
                         :risk :tier)))
    (is (= :critical (-> (decision/create-loop-escalation-checkpoint
                          (loop-state) {:risk-tier :critical})
                         :risk :tier)))))

(deftest loop-escalation-context-projects-loop-fields-test
  (testing "Context surfaces iteration, state, termination, task type, and error count"
    (let [errors [{:message "a"} {:message "b"} {:message "c"}]
          ls     (loop-state :loop/errors errors :loop/iteration 7)
          cp     (decision/create-loop-escalation-checkpoint ls {})
          ctx    (:context cp)]
      (is (= 7              (:loop/iteration ctx)))
      (is (= :escalated     (:loop/state     ctx)))
      (is (= {:reason :max-iterations} (:loop/termination ctx)))
      (is (= :implement     (:task/type      ctx)))
      (is (= 3              (:error-count    ctx))))))

;------------------------------------------------------------------------------ Layer 1
;; create-episode / update-episode

(deftest create-episode-status-mirrors-checkpoint-test
  (testing "Pending checkpoint ⇒ :pending episode; resolved ⇒ :resolved"
    (let [pending  (valid-control-plane-checkpoint)
          resolved (decision/resolve-checkpoint pending (valid-approve-response))
          ep1      (decision/create-episode pending)
          ep2      (decision/create-episode resolved)]
      (is (= :pending  (:episode/status ep1)))
      (is (= :resolved (:episode/status ep2))))))

(deftest create-episode-attaches-supervision-when-checkpoint-resolved-test
  (testing "When the checkpoint already carries :response, episode :supervision is set"
    (let [resolved (decision/resolve-checkpoint
                    (valid-control-plane-checkpoint)
                    (valid-approve-response :rationale "ok"))
          ep       (decision/create-episode resolved)]
      (is (some? (:supervision ep)))
      (is (= "ok" (-> ep :supervision :rationale))))))

(deftest create-episode-fresh-fields-test
  (testing "create-episode fills :episode/id (UUID), :created-at = :updated-at (inst)"
    (let [ep (decision/create-episode (valid-control-plane-checkpoint))]
      (is (uuid? (:episode/id ep)))
      (is (inst? (:episode/created-at ep)))
      (is (inst? (:episode/updated-at ep)))
      (is (= (:episode/created-at ep) (:episode/updated-at ep))))))

(deftest update-episode-on-resolve-marks-resolved-test
  (testing "Updating with a resolved checkpoint flips status to :resolved"
    (let [cp       (valid-control-plane-checkpoint)
          ep       (decision/create-episode cp)
          resolved (decision/resolve-checkpoint cp (valid-approve-response))
          updated  (decision/update-episode ep resolved)]
      (is (= :resolved (:episode/status updated)))
      (is (= :approve  (-> updated :supervision :type))))))

(deftest update-episode-with-downstream-outcome-marks-completed-test
  (testing "Supplying :downstream-outcome forces episode status :completed
            even when the checkpoint is still :pending"
    (let [cp      (valid-control-plane-checkpoint)
          ep      (decision/create-episode cp)
          updated (decision/update-episode ep cp
                                           {:downstream-outcome
                                            {:result :merged
                                             :timestamp (java.util.Date.)}})]
      (is (= :completed (:episode/status updated)))
      (is (= :merged   (-> updated :downstream-outcome :result))))))

(deftest update-episode-attaches-execution-result-when-given-test
  (testing "execution-result opt is stored on the episode"
    (let [cp      (valid-control-plane-checkpoint)
          ep      (decision/create-episode cp)
          updated (decision/update-episode ep cp
                                           {:execution-result
                                            {:exit-code 0 :duration-ms 1234}})]
      (is (= 0    (-> updated :execution-result :exit-code)))
      (is (= 1234 (-> updated :execution-result :duration-ms))))))

(deftest update-episode-bumps-updated-at-test
  (testing "update-episode advances :episode/updated-at past the original :created-at"
    (let [cp       (valid-control-plane-checkpoint)
          ep       (-> (decision/create-episode cp)
                       (assoc :episode/created-at
                              (java.util.Date. (- (System/currentTimeMillis) 1000))
                              :episode/updated-at
                              (java.util.Date. (- (System/currentTimeMillis) 1000))))
          updated  (decision/update-episode ep cp)]
      (is (.after ^java.util.Date (:episode/updated-at updated)
                  ^java.util.Date (:episode/updated-at ep))))))

;------------------------------------------------------------------------------ Layer 2
;; Schema validation through the public constructors

(deftest constructors-throw-on-malformed-input-test
  (testing "create-checkpoint throws when the produced map fails schema validation"
    ;; Empty map produces a checkpoint missing :source / :proposal.
    (is (thrown? clojure.lang.ExceptionInfo
                 (decision/create-checkpoint {}))))
  (testing "decision-response throws when authority-role somehow becomes invalid.
            (Pure-fn path doesn't normally produce this; we exercise via spec/validate
            directly to confirm the schema contract is enforced.)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (decision/validate spec/DecisionResponse
                                    {:type :approve :authority-role :alien})))))

(deftest constructed-checkpoints-conform-to-schema-test
  (testing "Each happy-path constructor produces a value that satisfies DecisionCheckpoint"
    (is (decision/valid? decision/DecisionCheckpoint
                         (valid-control-plane-checkpoint)))
    (is (decision/valid? decision/DecisionCheckpoint
                         (decision/create-loop-escalation-checkpoint (loop-state) {})))))

(deftest constructed-episode-conforms-to-schema-test
  (testing "create-episode produces a value satisfying DecisionEpisode"
    (let [ep (decision/create-episode (valid-control-plane-checkpoint))]
      (is (decision/valid? decision/DecisionEpisode ep)))))
