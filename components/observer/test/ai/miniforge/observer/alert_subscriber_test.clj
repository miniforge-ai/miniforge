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
(ns ai.miniforge.observer.alert-subscriber-test
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.observer.alert-subscriber :as sut]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} heartbeat [wf-id gap]
  {:event/type :workflow/phase-heartbeat
   :workflow/id wf-id
   :phase/gap-since-last-event-ms gap
   :event/timestamp #inst "2026-05-21T00:00:01Z"})

(def ^{:stratum 0} phase-rule
  {:alert/id :phase-gap
   :alert/type :phase-gap
   :alert/threshold {:threshold-ms 10}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} subscriber-publishes-alert-event
  (let [stream (es/create-event-stream {:sinks []})
        wf-id (random-uuid)
        handle (sut/start-alert-subscriber! stream wf-id [phase-rule])]
    (es/publish! stream (heartbeat (random-uuid) 99))
    (es/publish! stream (heartbeat wf-id 11))
    (let [events (es/get-events stream)
          alerts (filter #(= :observer/alert-fired (:event/type %)) events)]
      (is (= 3 (count events)))
      (is (= 1 (count alerts)))
      (is (= wf-id (:event/workflow-id (first alerts))))
      (is (= wf-id (:workflow/id (first alerts))))
      (is (= :phase-gap (:alert/type (first alerts)))))
    (sut/stop-alert-subscriber! stream handle)
    (es/publish! stream (heartbeat wf-id 12))
    (is (= 1 (count (filter #(= :observer/alert-fired (:event/type %))
                            (es/get-events stream)))))))

(deftest ^{:stratum 1} no-op-when-rules-empty
  (let [stream (es/create-event-stream {:sinks []})
        handle (sut/start-alert-subscriber! stream "wf" [])]
    (is (:noop? handle))
    (es/publish! stream (heartbeat (random-uuid) 99))
    (is (= 1 (count (es/get-events stream))))
    (is (nil? (sut/stop-alert-subscriber! stream nil)))))

(deftest ^{:stratum 1} rules-from-config-reads-observability-alerts
  (is (= [phase-rule]
         (sut/rules-from-config {:observability {:alerts [phase-rule]}})))
  (is (= [] (sut/rules-from-config {}))))
