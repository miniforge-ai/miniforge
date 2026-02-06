;; Copyright 2025 miniforge.ai
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

  (testing "workflow-failed captures error details"
    (let [stream (es/create-event-stream)
          wf-id (random-uuid)
          error {:message "LLM timeout" :type :timeout}
          event (es/workflow-failed stream wf-id error)]
      (is (= :workflow/failed (:event/type event)))
      (is (= "LLM timeout" (:workflow/failure-reason event)))
      (is (= error (:workflow/error-details event))))))

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
      (is (= "test" output)))))
