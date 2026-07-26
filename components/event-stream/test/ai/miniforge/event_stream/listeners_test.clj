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
(ns ai.miniforge.event-stream.listeners-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.listeners :as listeners]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} capability-sufficient-test
  (testing "observe meets observe requirement"
    (is (true? (listeners/capability-sufficient? :observe :observe))))
  (testing "advise meets observe requirement"
    (is (true? (listeners/capability-sufficient? :advise :observe))))
  (testing "control meets all requirements"
    (is (true? (listeners/capability-sufficient? :control :observe)))
    (is (true? (listeners/capability-sufficient? :control :advise)))
    (is (true? (listeners/capability-sufficient? :control :control))))
  (testing "observe does not meet advise requirement"
    (is (false? (listeners/capability-sufficient? :observe :advise))))
  (testing "observe does not meet control requirement"
    (is (false? (listeners/capability-sufficient? :observe :control)))))

(deftest ^{:stratum 0} event-requires-capability-test
  (testing ":public events require only :observe"
    (is (= :observe (listeners/event-requires-capability :workflow/started))))
  (testing ":internal events require :advise"
    (is (= :advise (listeners/event-requires-capability :agent/started)))
    (is (= :advise (listeners/event-requires-capability :llm/request)))
    (is (= :advise (listeners/event-requires-capability :llm/response)))
    (is (= :advise (listeners/event-requires-capability :tool/invoked))))
  (testing ":confidential events require :control"
    (is (= :control (listeners/event-requires-capability :control-action/requested)))))

(deftest ^{:stratum 0} internal-privacy-delivery-test
  (testing "observe-only listener does not receive :internal-privacy events"
    (let [stream (es/create-event-stream {:sinks []})
          received (atom [])
          wf-id (random-uuid)
          _lid (es/register-listener!
                stream
                {:listener/type :dashboard
                 :listener/capability :observe
                 :listener/identity {:principal "observer"}
                 :listener/callback (fn [event] (swap! received conj event))})]
      (es/publish! stream (es/agent-started stream wf-id :implementer))
      (is (empty? @received))))

  (testing "advise-capable listener does receive :internal-privacy events"
    (let [stream (es/create-event-stream {:sinks []})
          received (atom [])
          wf-id (random-uuid)
          _lid (es/register-listener!
                stream
                {:listener/type :enterprise
                 :listener/capability :advise
                 :listener/identity {:principal "advisor"}
                 :listener/callback (fn [event] (swap! received conj event))})]
      (es/publish! stream (es/agent-started stream wf-id :implementer))
      ;; @received also carries this listener's own :listener/attached event
      ;; (itself :internal-privacy, delivered because :advise qualifies).
      (is (some #(= :agent/started (:event/type %)) @received)))))

(deftest ^{:stratum 0} register-and-deregister-listener-test
  (testing "register-listener! returns a UUID and listener appears in list"
    (let [stream (es/create-event-stream {:sinks []})
          received (atom [])
          listener-id (es/register-listener!
                       stream
                       {:listener/type :dashboard
                        :listener/capability :observe
                        :listener/identity {:principal "test-user"}
                        :listener/callback (fn [event] (swap! received conj event))})]
      (is (uuid? listener-id))
      (is (= 1 (count (es/list-listeners stream))))
      (let [listener (listeners/get-listener stream listener-id)]
        (is (= :dashboard (:listener/type listener)))
        (is (= :observe (:listener/capability listener))))))

  (testing "deregister-listener! removes listener and stops delivery"
    (let [stream (es/create-event-stream {:sinks []})
          received (atom [])
          listener-id (es/register-listener!
                       stream
                       {:listener/type :watcher
                        :listener/capability :observe
                        :listener/identity {:principal "watcher-1"}
                        :listener/callback (fn [event] (swap! received conj event))})]
      ;; Should receive events
      (es/publish! stream (es/workflow-started stream (random-uuid)))
      (let [pre-count (count @received)]
        (is (pos? pre-count))
        ;; Deregister
        (es/deregister-listener! stream listener-id)
        (is (= 0 (count (es/list-listeners stream))))
        ;; Should no longer receive events
        (es/publish! stream (es/workflow-started stream (random-uuid)))
        ;; Only the attach/detach events + the one workflow event from before
        (is (= pre-count (count @received)))))))

(deftest ^{:stratum 0} listener-filtering-test
  (testing "listener with event-type filter only receives matching events"
    (let [stream (es/create-event-stream {:sinks []})
          received (atom [])
          wf-id (random-uuid)
          _lid (es/register-listener!
                stream
                {:listener/type :dashboard
                 :listener/capability :observe
                 :listener/identity {:principal "filter-test"}
                 :listener/filters {:event-types [:gate/passed :gate/failed]}
                 :listener/callback (fn [event] (swap! received conj event))})]
      ;; Publish events of various types
      (es/publish! stream (es/workflow-started stream wf-id))
      (es/publish! stream (es/gate-passed stream wf-id :syntax 100))
      (es/publish! stream (es/gate-failed stream wf-id :lint [{:msg "error"}]))
      (es/publish! stream (es/workflow-completed stream wf-id :success))
      ;; Should only have the two gate events
      (is (= 2 (count @received)))
      (is (every? #(#{:gate/passed :gate/failed} (:event/type %)) @received)))))

(deftest ^{:stratum 0} invalid-capability-test
  (testing "registering with invalid capability throws"
    (let [stream (es/create-event-stream {:sinks []})]
      (is (thrown? Exception
                   (es/register-listener!
                    stream
                    {:listener/type :dashboard
                     :listener/capability :superuser
                     :listener/identity {:principal "bad"}
                     :listener/callback (fn [_] nil)}))))))

(deftest ^{:stratum 0} submit-annotation-test
  (testing "advise-capable listener can submit annotation"
    (let [stream (es/create-event-stream {:sinks []})
          listener-id (es/register-listener!
                       stream
                       {:listener/type :enterprise
                        :listener/capability :advise
                        :listener/identity {:principal "advisor"}
                        :listener/callback (fn [_] nil)})
          event (es/submit-annotation!
                 stream listener-id
                 {:annotation/type :warning
                  :annotation/content "Budget nearing limit"})]
      (is (= :annotation/created (:event/type event)))
      (is (= :warning (:annotation/type event)))))

  (testing "observe-only listener cannot submit annotation"
    (let [stream (es/create-event-stream {:sinks []})
          listener-id (es/register-listener!
                       stream
                       {:listener/type :dashboard
                        :listener/capability :observe
                        :listener/identity {:principal "watcher"}
                        :listener/callback (fn [_] nil)})]
      (is (thrown? Exception
                   (es/submit-annotation!
                    stream listener-id
                    {:annotation/type :warning
                     :annotation/content "should fail"})))))

  (testing "annotation for non-existent listener throws"
    (let [stream (es/create-event-stream {:sinks []})]
      (is (thrown? Exception
                   (es/submit-annotation!
                    stream (random-uuid)
                    {:annotation/type :note
                     :annotation/content "no such listener"}))))))
