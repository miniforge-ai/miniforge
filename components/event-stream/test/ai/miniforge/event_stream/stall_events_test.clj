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

(ns ai.miniforge.event-stream.stall-events-test
  "Tests for GROUP 1+4 foundation: agent-stream-stalled constructor and
   phase-completed :phase/termination-reason extension."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.interface :as events-iface]
   [ai.miniforge.event-stream.interface.events :as events]))

;------------------------------------------------------------------------------ Helpers

(defn no-op-stream
  "Create a stream with no sinks — event constructors only build maps."
  []
  (core/create-event-stream {:sinks []}))

;------------------------------------------------------------------------------ agent-stream-stalled

(deftest agent-stream-stalled-type-test
  (testing "event type is :agent/stream-stalled"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/agent-stream-stalled stream wf-id :implement 95000 :codex)]
      (is (= :agent/stream-stalled (:event/type event))))))

(deftest agent-stream-stalled-fields-test
  (testing "carries phase-id, gap-duration-ms, and backend"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/agent-stream-stalled stream wf-id :implement 95000 :codex)]
      (is (= :implement (:phase/id event)))
      (is (= 95000 (:stream/gap-duration-ms event)))
      (is (= :codex (:agent/backend event)))))

  (testing "workflow-id is propagated"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/agent-stream-stalled stream wf-id :verify 120000 :claude)]
      (is (= wf-id (:workflow/id event)))))

  (testing "message includes phase-id and gap-duration"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/agent-stream-stalled stream wf-id :plan 88000 :claude)]
      (is (string? (:message event)))
      (is (re-find #"plan" (:message event)))
      (is (re-find #"88000" (:message event)))))

  (testing "envelope fields are present"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/agent-stream-stalled stream wf-id :implement 90001 :codex)]
      (is (uuid? (:event/id event)))
      (is (inst? (:event/timestamp event)))
      (is (= core/event-version (:event/version event)))
      (is (number? (:event/sequence-number event))))))

(deftest agent-stream-stalled-works-with-any-backend-test
  (testing "backend keyword is preserved as-is"
    (doseq [backend [:codex :claude :openai :local]]
      (let [stream (no-op-stream)
            event  (core/agent-stream-stalled stream (random-uuid) :implement 90000 backend)]
        (is (= backend (:agent/backend event)))))))

;------------------------------------------------------------------------------ phase-completed :phase/termination-reason

(deftest phase-completed-termination-reason-test
  (testing "termination-reason :normal is passed through"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :implement
                                       {:outcome :success
                                        :phase/termination-reason :normal})]
      (is (= :normal (:phase/termination-reason event)))))

  (testing "termination-reason :agent-stalled is passed through"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :implement
                                       {:outcome :failure
                                        :phase/termination-reason :agent-stalled})]
      (is (= :agent-stalled (:phase/termination-reason event)))))

  (testing "termination-reason :curator-rejected is passed through"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :verify
                                       {:outcome :failure
                                        :phase/termination-reason :curator-rejected})]
      (is (= :curator-rejected (:phase/termination-reason event)))))

  (testing "termination-reason :tool-error is passed through"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :implement
                                       {:outcome :failure
                                        :phase/termination-reason :tool-error})]
      (is (= :tool-error (:phase/termination-reason event)))))

  (testing "absence of termination-reason leaves key absent"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :plan {:outcome :success})]
      (is (not (contains? event :phase/termination-reason)))))

  (testing "termination-reason does not displace existing result fields"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          event  (core/phase-completed stream wf-id :implement
                                       {:outcome :success
                                        :duration-ms 4200
                                        :phase/termination-reason :normal})]
      (is (= :success (:phase/outcome event)))
      (is (= 4200 (:phase/duration-ms event)))
      (is (= :normal (:phase/termination-reason event))))))

;------------------------------------------------------------------------------ Re-export verification

(deftest interface-events-re-export-test
  (testing "agent-stream-stalled is exported through interface.events"
    (is (fn? events/agent-stream-stalled)))

  (testing "agent-stream-stalled is exported through interface"
    (is (fn? events-iface/agent-stream-stalled)))

  (testing "re-exported function produces the same result as core"
    (let [stream (no-op-stream)
          wf-id  (random-uuid)
          direct (core/agent-stream-stalled stream wf-id :implement 90000 :codex)
          via-if (events/agent-stream-stalled stream wf-id :implement 90000 :codex)]
      ;; Type, phase, gap, and backend must match; ids and timestamps differ
      (is (= (:event/type direct) (:event/type via-if)))
      (is (= (:phase/id direct) (:phase/id via-if)))
      (is (= (:stream/gap-duration-ms direct) (:stream/gap-duration-ms via-if)))
      (is (= (:agent/backend direct) (:agent/backend via-if))))))
