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

(ns ai.miniforge.event-stream.typed-acts-schema-test
  "Schemas for the typed handoff acts: widened phase outcomes, the meta-loop
   REFUSE, and inter-agent messages."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.schema :as schema]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]))

(defn- stream [] (es/create-event-stream {:sinks []}))

(deftest phase-completed-accepts-blocked-and-redirected-test
  (testing "the widened :phase/outcome enum admits :blocked with a reason and :redirected"
    (let [s          (stream)
          wid        (random-uuid)
          blocked    (es/phase-completed s wid :plan
                                         {:outcome :blocked
                                          :phase/blocked-reason :missing-input
                                          :duration-ms 1})
          redirected (es/phase-completed s wid :review
                                         {:outcome :redirected :duration-ms 1})]
      (is (= :blocked (:phase/outcome blocked)))
      (is (= :missing-input (:phase/blocked-reason blocked)))
      (is (m/validate schema/PhaseCompleted blocked))
      (is (= :redirected (:phase/outcome redirected)))
      (is (m/validate schema/PhaseCompleted redirected)))))

(deftest meta-loop-halt-requested-is-valid-test
  (testing "meta-loop-halt-requested builds a schema-valid REFUSE event"
    (let [ev (es/meta-loop-halt-requested (stream) (random-uuid)
                                          :conflict-detector :conflict
                                          {:detail "merge conflict" :phase :implement})]
      (is (= :meta-loop/halt-requested (:event/type ev)))
      (is (= :conflict (:halt/reason-code ev)))
      (is (= :conflict-detector (:halt/halting-agent ev)))
      (is (m/validate schema/MetaLoopHaltRequested ev)))))

(deftest inter-agent-messages-are-valid-test
  (testing "inter-agent message constructors satisfy the now-defined schemas"
    (let [s    (stream)
          wid  (random-uuid)
          sent (es/inter-agent-message-sent s wid :implementer :planner :clarification-request)
          recv (es/inter-agent-message-received s wid :planner :implementer :clarification-response)]
      (is (m/validate schema/AgentMessageSent sent))
      (is (m/validate schema/AgentMessageReceived recv)))))

(deftest refusal-reason-is-closed-test
  (testing "RefusalReason admits the shared vocabulary and rejects unknowns"
    (is (m/validate schema/RefusalReason :budget-exhausted))
    (is (not (m/validate schema/RefusalReason :totally-made-up)))))
