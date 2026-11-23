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
(ns ai.miniforge.observer.completed-tool-call-test
  "A tool call the provider reports only once it has finished (Codex
   item.completed) must not stay outstanding in the alert state, where a
   tool-call-timeout rule would later report it as timed out."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.observer.alerts :as alerts]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} timeout-rules
  [{:alert/id :timeout :alert/type :tool-call-timeout
    :alert/threshold {:threshold-ms 1} :alert/severity :critical}])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observe-callback-events
  "Feed `parsed` stream events through the streaming callback, then every
   published event through the alert rules. Returns the alert state."
  [parsed]
  (let [stream (es/create-event-stream {:sinks []})
        callback (es/create-streaming-callback stream (random-uuid) :implementer)
        state (alerts/create-alert-state)]
    (doseq [p parsed] (callback p))
    (doseq [event (es/get-events stream {})]
      (alerts/evaluate-rules timeout-rules event state))
    state))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} completed-native-call-is-not-left-outstanding-test
  (testing "a completed Codex command closes its call and records its outcome"
    (let [state (observe-callback-events
                 [{:tool-use true :tool-completed true :tool-error? false
                   :tool-name "command_execution" :tool-call-id "item_1"}
                  {:tool-use true :tool-completed true :tool-error? true
                   :tool-name "command_execution" :tool-call-id "item_2"}
                  {:tool-use true :tool-completed true
                   :tool-name "web_search" :tool-call-id "item_3"}])]
      (is (= {} (:outstanding-calls @state)))
      (is (= [true false] (:tool-outcomes @state))
          "web_search reports no status, so no outcome is recorded")
      (is (empty? (alerts/evaluate-rules
                   timeout-rules
                   {:event/type :workflow/phase-heartbeat
                    :event/timestamp #inst "2099-01-01T00:00:00Z"}
                   state))
          "no timeout fires for calls that already finished"))))

(deftest ^{:stratum 2} streamed-tool-use-still-waits-for-its-result-test
  (testing "a Claude tool_use stays outstanding until its tool_result arrives"
    (let [state (observe-callback-events
                 [{:tool-use true :tool-name "Bash" :tool-call-id "toolu_1"}])]
      (is (contains? (:outstanding-calls @state) "toolu_1")))))
