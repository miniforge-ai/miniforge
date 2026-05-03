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

(ns ai.miniforge.event-stream.interface-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]))

(deftest create-event-stream-test
  (testing "creates event stream with initial state"
    (let [stream (es/create-event-stream)]
      (is (some? stream))
      (is (= [] (es/get-events stream)))
      (is (map? @stream))
      (is (contains? @stream :events))
      (is (contains? @stream :subscribers)))))

(deftest publish-and-subscribe-test
  (testing "subscribers receive published events"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          received (atom [])]
      ;; Subscribe
      (es/subscribe! stream :test-sub
                     (fn [event] (swap! received conj event)))
      ;; Publish
      (es/publish! stream (es/workflow-started stream wf-id))
      ;; Verify
      (is (= 1 (count @received)))
      (is (= :workflow/started (:event/type (first @received))))))

  (testing "filtered subscription only receives matching events"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          received (atom [])]
      ;; Subscribe with filter
      (es/subscribe! stream :phase-only
                     (fn [event] (swap! received conj event))
                     (fn [event] (= :workflow/phase-started (:event/type event))))
      ;; Publish various events
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/workflow-completed stream wf-id :success))
      ;; Verify only phase event received
      (is (= 1 (count @received)))
      (is (= :workflow/phase-started (:event/type (first @received))))))

  (testing "unsubscribe stops event delivery"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          received (atom [])]
      (es/subscribe! stream :test-sub
                     (fn [event] (swap! received conj event)))
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/unsubscribe! stream :test-sub)
      (es/publish! stream (es/phase-started stream wf-id :plan))
      ;; Only first event received
      (is (= 1 (count @received))))))

(deftest event-envelope-test
  (testing "events have required N3 envelope fields"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/workflow-started stream wf-id)]
      (is (= :workflow/started (:event/type event)))
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (string? (:event/version event)))
      (is (int? (:event/sequence-number event)))
      (is (= wf-id (:workflow/id event)))
      (is (string? (:message event)))))

  (testing "sequence numbers increment per workflow"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          e1 (es/workflow-started stream wf-id)
          e2 (es/phase-started stream wf-id :plan)
          e3 (es/phase-completed stream wf-id :plan)]
      (is (= 0 (:event/sequence-number e1)))
      (is (= 1 (:event/sequence-number e2)))
      (is (= 2 (:event/sequence-number e3)))))

  (testing "different workflows have independent sequences"
    (let [stream (es/create-event-stream)
          wf-id-1 (random-uuid)
          wf-id-2 (random-uuid)
          e1 (es/workflow-started stream wf-id-1)
          e2 (es/workflow-started stream wf-id-2)
          e3 (es/phase-started stream wf-id-1 :plan)]
      (is (= 0 (:event/sequence-number e1)))
      (is (= 0 (:event/sequence-number e2)))
      (is (= 1 (:event/sequence-number e3))))))

(deftest workflow-event-constructors-test
  (testing "workflow-started includes spec when provided"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          spec {:name "test" :version "1.0.0"}
          event (es/workflow-started stream wf-id spec)]
      (is (= spec (:workflow/spec event)))))

  (testing "phase-started includes phase and context"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          ctx {:budget {:tokens 10000}}
          event (es/phase-started stream wf-id :implement ctx)]
      (is (= :implement (:workflow/phase event)))
      (is (= ctx (:phase/context event)))))

  (testing "phase-completed includes outcome and metrics"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          result {:outcome :success :duration-ms 5000}
          event (es/phase-completed stream wf-id :plan result)]
      (is (= :plan (:workflow/phase event)))
      (is (= :success (:phase/outcome event)))
      (is (= 5000 (:phase/duration-ms event)))))

  (testing "workflow-completed includes status and duration"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/workflow-completed stream wf-id :success 120000)]
      (is (= :success (:workflow/status event)))
      (is (= 120000 (:workflow/duration-ms event)))))

  (testing "phase-completed includes tokens and cost"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/phase-completed stream wf-id :implement
                                     {:outcome :success :duration-ms 8000
                                      :tokens 2500 :cost-usd 0.08})]
      (is (= 2500 (:phase/tokens event)))
      (is (= 0.08 (:phase/cost-usd event)))))

  (testing "workflow-completed includes tokens and cost via opts"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/workflow-completed stream wf-id :success 120000
                                        {:tokens 5000 :cost-usd 0.25})]
      (is (= 5000 (:workflow/tokens event)))
      (is (= 0.25 (:workflow/cost-usd event)))))

  (testing "workflow-failed captures error details"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          error {:message "LLM timeout" :type :timeout}
          event (es/workflow-failed stream wf-id error)]
      (is (= :workflow/failed (:event/type event)))
      (is (= "LLM timeout" (:workflow/failure-reason event)))
      (is (= error (:workflow/error-details event))))))

(deftest dependency-event-constructors-test
  (testing "dependency health events are exposed on the public interface"
    (let [stream (es/create-event-stream)
          dependency {:dependency/id :anthropic
                      :dependency/source :external-provider
                      :dependency/kind :provider
                      :dependency/status :degraded
                      :dependency/failure-count 1
                      :dependency/window-size 5
                      :dependency/incident-counts {:degraded 1}}
          updated (es/dependency-health-updated stream dependency :healthy)
          recovered (es/dependency-recovered stream
                                             (assoc dependency
                                                    :dependency/status :healthy
                                                    :dependency/failure-count 0
                                                    :dependency/incident-counts {})
                                             :degraded)]
      (is (= :dependency/health-updated (:event/type updated)))
      (is (= :healthy (:dependency/previous-status updated)))
      (is (= :dependency/recovered (:event/type recovered)))
      (is (= :healthy (:dependency/status recovered))))))

(deftest agent-event-constructors-test
  (testing "agent-chunk captures delta and done status"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (let [chunk (es/agent-chunk stream wf-id :planner "Hello")]
        (is (= :agent/chunk (:event/type chunk)))
        (is (= :planner (:agent/id chunk)))
        (is (= "Hello" (:chunk/delta chunk)))
        (is (nil? (:chunk/done? chunk))))
      (let [done-chunk (es/agent-chunk stream wf-id :planner "" true)]
        (is (true? (:chunk/done? done-chunk))))))

  (testing "agent-status captures status type"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/agent-status stream wf-id :implementer :generating "Writing code")]
      (is (= :agent/status (:event/type event)))
      (is (= :implementer (:agent/id event)))
      (is (= :generating (:status/type event)))
      (is (= "Writing code" (:message event))))))

(deftest llm-event-constructors-test
  (testing "llm-request captures model and tokens"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/llm-request stream wf-id :planner "claude-sonnet-4" 2400)]
      (is (= :llm/request (:event/type event)))
      (is (= :planner (:agent/id event)))
      (is (= "claude-sonnet-4" (:llm/model event)))
      (is (= 2400 (:llm/prompt-tokens event)))
      (is (uuid? (:llm/request-id event)))))

  (testing "llm-response captures metrics"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          req-id (random-uuid)
          metrics {:completion-tokens 850 :duration-ms 3200}
          event (es/llm-response stream wf-id :planner "claude-sonnet-4" req-id metrics)]
      (is (= :llm/response (:event/type event)))
      (is (= req-id (:llm/request-id event)))
      (is (= 850 (:llm/completion-tokens event)))
      (is (= 3200 (:llm/duration-ms event))))))

(deftest get-events-test
  (testing "get-events returns all events"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/phase-completed stream wf-id :plan))
      (is (= 3 (count (es/get-events stream))))))

  (testing "get-events filters by workflow-id"
    (let [stream (es/create-event-stream)
          wf-id-1 (random-uuid)
          wf-id-2 (random-uuid)]
      (es/publish! stream (es/workflow-started stream wf-id-1))
      (es/publish! stream (es/workflow-started stream wf-id-2))
      (es/publish! stream (es/phase-started stream wf-id-1 :plan))
      (is (= 2 (count (es/get-events stream {:workflow-id wf-id-1}))))
      (is (= 1 (count (es/get-events stream {:workflow-id wf-id-2}))))))

  (testing "get-events filters by event-type"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/phase-completed stream wf-id :plan))
      (is (= 1 (count (es/get-events stream {:event-type :workflow/started}))))))

  (testing "get-events supports offset and limit"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/phase-started stream wf-id :plan))
      (es/publish! stream (es/phase-completed stream wf-id :plan))
      (is (= 2 (count (es/get-events stream {:limit 2}))))
      (is (= 1 (count (es/get-events stream {:offset 2}))))
      (is (= 1 (count (es/get-events stream {:offset 1 :limit 1})))))))

(deftest get-latest-status-test
  (testing "returns most recent status event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (es/publish! stream (es/agent-status stream wf-id :planner :thinking "First"))
      (es/publish! stream (es/agent-status stream wf-id :planner :generating "Second"))
      (let [latest (es/get-latest-status stream wf-id)]
        (is (= :generating (:status/type latest)))
        (is (= "Second" (:message latest))))))

  (testing "filters by agent-id"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)]
      (es/publish! stream (es/agent-status stream wf-id :planner :thinking "Plan"))
      (es/publish! stream (es/agent-status stream wf-id :implementer :generating "Impl"))
      (let [planner-status (es/get-latest-status stream wf-id :planner)]
        (is (= :planner (:agent/id planner-status))))
      (let [impl-status (es/get-latest-status stream wf-id :implementer)]
        (is (= :implementer (:agent/id impl-status)))))))

(deftest create-streaming-callback-test
  (testing "callback publishes agent-chunk events"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          callback (es/create-streaming-callback stream wf-id :planner)]
      ;; Call the callback
      (callback {:delta "Hello" :done? false})
      (callback {:delta " world" :done? false})
      (callback {:delta "" :done? true})
      ;; Verify events
      (let [chunks (es/get-events stream {:event-type :agent/chunk})]
        (is (= 3 (count chunks)))
        (is (= "Hello" (:chunk/delta (first chunks))))
        (is (= " world" (:chunk/delta (second chunks))))
        (is (true? (:chunk/done? (last chunks)))))))

  (testing "callback with print option writes to stdout"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          callback (es/create-streaming-callback stream wf-id :planner {:print? true})
          output (with-out-str
                   (callback {:delta "test" :done? false}))]
      (is (= "test" output))))

  (testing "tool-use callback prints a visible tool line and publishes structured events"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          callback (es/create-streaming-callback stream wf-id :planner {:print? true})
          output (with-out-str
                   (callback {:tool-use true
                              :tool-name "context_read"
                              :content ""}))]
      (is (= "\n[tool] context_read\n" output))
      (is (= 1 (count (es/get-events stream {:event-type :agent/tool-call}))))
      (is (= 1 (count (es/get-events stream {:event-type :agent/status})))))))

;; --------------------------------------------------------------------------- New N3 event constructors

(deftest agent-lifecycle-event-constructors-test
  (testing "agent-started creates event with agent-id and optional context"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/agent-started stream wf-id :planner {:budget 10000})]
      (is (= :agent/started (:event/type event)))
      (is (= :planner (:agent/id event)))
      (is (= {:budget 10000} (:agent/context event)))
      (is (uuid? (:event/id event)))))

  (testing "agent-completed creates event with optional result"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/agent-completed stream wf-id :implementer {:tokens-used 5000})]
      (is (= :agent/completed (:event/type event)))
      (is (= :implementer (:agent/id event)))
      (is (= {:tokens-used 5000} (:agent/result event)))))

  (testing "agent-failed creates event with optional error"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/agent-failed stream wf-id :reviewer {:message "timeout"})]
      (is (= :agent/failed (:event/type event)))
      (is (= :reviewer (:agent/id event)))
      (is (= {:message "timeout"} (:agent/error event))))))

(deftest gate-lifecycle-event-constructors-test
  (testing "gate-started creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/gate-started stream wf-id :lint)]
      (is (= :gate/started (:event/type event)))
      (is (= :lint (:gate/id event)))))

  (testing "gate-passed creates event with duration"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/gate-passed stream wf-id :syntax 150)]
      (is (= :gate/passed (:event/type event)))
      (is (= :syntax (:gate/id event)))
      (is (= 150 (:gate/duration-ms event)))))

  (testing "gate-failed creates event with violations"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          violations [{:line 10 :message "unused var"}]
          event (es/gate-failed stream wf-id :lint violations)]
      (is (= :gate/failed (:event/type event)))
      (is (= :lint (:gate/id event)))
      (is (= violations (:gate/violations event))))))

(deftest tool-lifecycle-event-constructors-test
  (testing "tool-invoked creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/tool-invoked stream wf-id :implementer :tools/read-file {:path "src/core.clj"})]
      (is (= :tool/invoked (:event/type event)))
      (is (= :implementer (:agent/id event)))
      (is (= :tools/read-file (:tool/id event)))
      (is (= {:path "src/core.clj"} (:tool/params-summary event)))))

  (testing "tool-completed creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/tool-completed stream wf-id :implementer :tools/write-file {:success true})]
      (is (= :tool/completed (:event/type event)))
      (is (= :tools/write-file (:tool/id event)))
      (is (= {:success true} (:tool/result-summary event))))))

(deftest milestone-event-constructor-test
  (testing "milestone-reached creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/milestone-reached stream wf-id :tests-passing "All 42 tests pass")]
      (is (= :workflow/milestone-reached (:event/type event)))
      (is (= :tests-passing (:milestone/id event)))
      (is (= "All 42 tests pass" (:message event))))))

(deftest task-lifecycle-event-constructors-test
  (testing "task-state-changed creates event with from/to states"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          dag-id (random-uuid)
          task-id (random-uuid)
          event (es/task-state-changed stream wf-id dag-id task-id :pending :ready)]
      (is (= :task/state-changed (:event/type event)))
      (is (= dag-id (:dag/id event)))
      (is (= task-id (:task/id event)))
      (is (= :pending (:task/from-state event)))
      (is (= :ready (:task/to-state event)))))

  (testing "task-frontier-entered creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/task-frontier-entered stream wf-id (random-uuid) (random-uuid) 3)]
      (is (= :task/frontier-entered (:event/type event)))
      (is (= 3 (:task/frontier-size event)))))

  (testing "task-skip-propagated creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          cause (random-uuid)
          event (es/task-skip-propagated stream wf-id (random-uuid) (random-uuid) cause)]
      (is (= :task/skip-propagated (:event/type event)))
      (is (= cause (:task/cause-task event))))))

(deftest inter-agent-message-event-constructors-test
  (testing "inter-agent-message-sent creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/inter-agent-message-sent stream wf-id :planner :implementer :suggestion)]
      (is (= :agent/message-sent (:event/type event)))
      (is (= :planner (:from-agent/id event)))
      (is (= :implementer (:to-agent/id event)))
      (is (= :suggestion (:message/type event)))))

  (testing "inter-agent-message-received creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          event (es/inter-agent-message-received stream wf-id :planner :implementer)]
      (is (= :agent/message-received (:event/type event)))
      (is (= :planner (:from-agent/id event)))
      (is (= :implementer (:to-agent/id event))))))

(deftest listener-event-constructors-test
  (testing "listener-attached creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          lid (random-uuid)
          event (es/listener-attached stream wf-id lid :dashboard :observe)]
      (is (= :listener/attached (:event/type event)))
      (is (= lid (:listener/id event)))
      (is (= :dashboard (:listener/type event)))
      (is (= :observe (:listener/capability event)))))

  (testing "listener-detached creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          lid (random-uuid)
          event (es/listener-detached stream wf-id lid "timeout")]
      (is (= :listener/detached (:event/type event)))
      (is (= lid (:listener/id event)))
      (is (= "timeout" (:listener/reason event)))))

  (testing "annotation-created creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          lid (random-uuid)
          event (es/annotation-created stream wf-id lid :warning "slow response")]
      (is (= :annotation/created (:event/type event)))
      (is (= lid (:listener/id event)))
      (is (= :warning (:annotation/type event)))
      (is (= "slow response" (:annotation/content event))))))

(deftest control-action-event-constructors-test
  (testing "control-action-requested creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          aid (random-uuid)
          event (es/control-action-requested stream wf-id aid :pause {:principal "admin"})]
      (is (= :control-action/requested (:event/type event)))
      (is (= aid (:action/id event)))
      (is (= :pause (:action/type event)))
      (is (= {:principal "admin"} (:action/requester event)))))

  (testing "control-action-executed creates event"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          aid (random-uuid)
          event (es/control-action-executed stream wf-id aid {:status :success})]
      (is (= :control-action/executed (:event/type event)))
      (is (= aid (:action/id event)))
      (is (= {:status :success} (:action/result event))))))
