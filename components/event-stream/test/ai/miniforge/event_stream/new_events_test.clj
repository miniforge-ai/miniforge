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

(ns ai.miniforge.event-stream.new-events-test
  "Tests for GROUP 1+2 foundation event constructors:
     :agent/tool-call-started, :tool/call-completed, :workflow/phase-heartbeat.

   Verifies that the new constructors:
     - Emit the correct :event/type keyword
     - Carry required payload fields
     - Are backward-compatible (legacy :agent/tool-call is unaffected)
     - Are accessible through the public interface"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.event-stream.interface :as es]))

(defn- stream [] (es/create-event-stream))

;------------------------------------------------------------------------------ :agent/tool-call-started

(deftest agent-tool-call-started-event-type
  (testing "emits :agent/tool-call-started event type"
    (let [ev (es/agent-tool-call-started
              (stream) (random-uuid) :implementer
              {:tool/name "Read" :tool/call-id "tc_001"
               :tool/args-digest {:digest/sha256 "abc" :digest/original-size 42}})]
      (is (= :agent/tool-call-started (:event/type ev))))))

(deftest agent-tool-call-started-required-fields
  (testing "carries tool name, call-id, and agent-id"
    (let [wf-id (random-uuid)
          ev    (es/agent-tool-call-started
                 (stream) wf-id :planner
                 {:tool/name "Write" :tool/call-id "tc_002"
                  :tool/args-digest {:digest/sha256 "abc" :digest/original-size 10}})]
      (is (= wf-id (:workflow/id ev)))
      (is (= :planner (:agent/id ev)))
      (is (= "Write" (:tool/name ev)))
      (is (= "tc_002" (:tool/call-id ev)))
      (is (map? (:tool/args-digest ev))))))

(deftest agent-tool-call-started-optional-fields-absent-when-nil
  (testing "optional keys are absent when not supplied"
    (let [ev (es/agent-tool-call-started
              (stream) (random-uuid) :reviewer {})]
      (is (= :agent/tool-call-started (:event/type ev)))
      (is (not (contains? ev :tool/name)))
      (is (not (contains? ev :tool/call-id)))
      (is (not (contains? ev :tool/args-digest))))))

(deftest agent-tool-call-started-envelope-fields
  (testing "envelope fields are present and typed correctly"
    (let [ev (es/agent-tool-call-started
              (stream) (random-uuid) :tester
              {:tool/name "Bash" :tool/call-id "tc_003"
               :tool/args-digest {:digest/sha256 "def" :digest/original-size 5}})]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

;------------------------------------------------------------------------------ :tool/call-completed

(deftest tool-call-completed-event-type
  (testing "emits :tool/call-completed event type"
    (let [ev (es/tool-call-completed
              (stream) (random-uuid)
              {:tool/call-id "tc_001" :tool/success? true
               :tool/result-digest {:digest/sha256 "xyz" :digest/original-size 100}
               :tool/duration-ms 42})]
      (is (= :tool/call-completed (:event/type ev))))))

(deftest tool-call-completed-success-fields
  (testing "success path carries all expected keys"
    (let [wf-id (random-uuid)
          ev    (es/tool-call-completed
                 (stream) wf-id
                 {:tool/call-id "tc_002" :tool/success? true
                  :tool/result-digest {:digest/sha256 "rrr" :digest/original-size 50}
                  :tool/duration-ms 123})]
      (is (= wf-id (:workflow/id ev)))
      (is (= "tc_002" (:tool/call-id ev)))
      (is (true? (:tool/success? ev)))
      (is (= 123 (:tool/duration-ms ev)))
      (is (map? (:tool/result-digest ev))))))

(deftest tool-call-completed-failure-carries-error
  (testing "failure path carries :tool/error map"
    (let [ev (es/tool-call-completed
              (stream) (random-uuid)
              {:tool/call-id "tc_003" :tool/success? false
               :tool/result-digest {:digest/sha256 "yyy" :digest/original-size 0}
               :tool/duration-ms 10
               :tool/error {:message "file not found" :code 404}})]
      (is (false? (:tool/success? ev)))
      (is (= {:message "file not found" :code 404} (:tool/error ev))))))

(deftest tool-call-completed-no-error-on-success
  (testing "error key absent when not supplied"
    (let [ev (es/tool-call-completed
              (stream) (random-uuid)
              {:tool/call-id "tc_004" :tool/success? true
               :tool/result-digest {} :tool/duration-ms 5})]
      (is (not (contains? ev :tool/error))))))

;------------------------------------------------------------------------------ :workflow/phase-heartbeat

(deftest phase-heartbeat-event-type
  (testing "emits :workflow/phase-heartbeat event type"
    (let [now (java.util.Date.)
          ev  (es/phase-heartbeat
               (stream) (random-uuid) :implement
               {:phase/active-since              now
                :phase/events-emitted            17
                :phase/last-event-at             now
                :phase/gap-since-last-event-ms   3000})]
      (is (= :workflow/phase-heartbeat (:event/type ev))))))

(deftest phase-heartbeat-required-fields
  (testing "carries phase timing and event-count fields"
    (let [active-since (java.util.Date.)
          last-event   (java.util.Date.)
          wf-id        (random-uuid)
          ev           (es/phase-heartbeat
                        (stream) wf-id :plan
                        {:phase/active-since              active-since
                         :phase/events-emitted            42
                         :phase/last-event-at             last-event
                         :phase/gap-since-last-event-ms   5000})]
      (is (= wf-id (:workflow/id ev)))
      (is (= :plan (:workflow/phase ev)))
      (is (= active-since (:phase/active-since ev)))
      (is (= 42 (:phase/events-emitted ev)))
      (is (= last-event (:phase/last-event-at ev)))
      (is (= 5000 (:phase/gap-since-last-event-ms ev))))))

(deftest phase-heartbeat-zero-gap-is-valid
  (testing "gap of 0ms is a valid value (not treated as falsy)"
    (let [now (java.util.Date.)
          ev  (es/phase-heartbeat
               (stream) (random-uuid) :review
               {:phase/active-since              now
                :phase/events-emitted            0
                :phase/last-event-at             now
                :phase/gap-since-last-event-ms   0})]
      (is (= 0 (:phase/gap-since-last-event-ms ev)))
      (is (= 0 (:phase/events-emitted ev))))))

;------------------------------------------------------------------------------ backward compatibility

(deftest legacy-agent-tool-call-unaffected
  (testing "legacy :agent/tool-call constructor still works and has the old event type"
    (let [ev (es/agent-tool-call (stream) (random-uuid) :implementer
                                 {:tool-name "Read" :tool-call-id "old_001"})]
      (is (= :agent/tool-call (:event/type ev)))
      (is (= "Read" (:tool/name ev)))
      (is (= "old_001" (:tool/call-id ev))))))

(deftest new-events-have-distinct-types-from-legacy
  (testing "new event types do not collide with legacy :agent/tool-call"
    (let [s     (stream)
          wf-id (random-uuid)
          old   (es/agent-tool-call s wf-id :planner {:tool-name "Bash"})
          new   (es/agent-tool-call-started s wf-id :planner
                                            {:tool/name "Bash" :tool/call-id "x"})]
      (is (not= (:event/type old) (:event/type new))))))
