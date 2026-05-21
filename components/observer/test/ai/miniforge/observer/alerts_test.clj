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

(ns ai.miniforge.observer.alerts-test
  (:require
   [ai.miniforge.observer.alerts :as alerts]
   [ai.miniforge.observer.interface :as observer]
   [clojure.test :refer [deftest is]]))

(defn- heartbeat [gap]
  (cond-> {:event/type :workflow/phase-heartbeat}
    gap (assoc :phase/gap-since-last-event-ms gap)))

(defn- start [id]
  {:event/type :agent/tool-call-started :tool/call-id id
   :event/timestamp #inst "2026-05-21T00:00:00Z"})

(defn- done [id ok?]
  (cond-> {:event/type :tool/call-completed :tool/call-id id}
    (some? ok?) (assoc :tool/success? ok?)))

(defn- phase-rule [ms]
  {:alert/id :phase-gap :alert/type :phase-gap :alert/threshold {:threshold-ms ms}})

(defn- timeout-rule [ms]
  {:alert/id :timeout :alert/type :tool-call-timeout
   :alert/threshold {:threshold-ms ms} :alert/severity :critical})

(defn- error-rule [pct]
  {:alert/id :error-rate :alert/type :tool-error-rate :alert/threshold {:threshold-pct pct}})

(deftest state-and-phase-gap
  (let [state (alerts/create-alert-state)]
    (is (= {:outstanding-calls {} :tool-outcomes []} @state))
    (is (empty? (alerts/evaluate-rules [(phase-rule 10)] (heartbeat 10) state)))
    (let [fired (alerts/evaluate-rules [(phase-rule 10)] (heartbeat 11) state)]
      (is (= [:phase-gap] (mapv :alert/type fired)))
      (is (= :warning (:alert/severity (first fired))))
      (is (inst? (:alert/timestamp (first fired)))))))

(deftest tool-timeout-tracks-start-and-completion
  (let [state (alerts/create-alert-state)
        rules [(timeout-rule 100000)]]
    (alerts/evaluate-rules rules (start "c1") state)
    (is (contains? (:outstanding-calls @state) "c1"))
    (alerts/evaluate-rules rules (done "c1" true) state)
    (is (not (contains? (:outstanding-calls @state) "c1")))
    (is (= [true] (:tool-outcomes @state)))))

(deftest tool-timeout-fires-on-heartbeat
  (let [state (alerts/create-alert-state)]
    (alerts/evaluate-rules [(timeout-rule 1)] (start "old") state)
    (let [tick (assoc (heartbeat nil) :event/timestamp #inst "2026-05-21T00:00:02Z")
          fired (alerts/evaluate-rules [(timeout-rule 1)] tick state)]
      (is (= [:tool-call-timeout] (mapv :alert/type fired)))
      (is (= :critical (:alert/severity (first fired))))
      (is (empty? (alerts/evaluate-rules [(timeout-rule 1)] tick state))))))

(deftest error-rate-fires-only-on-heartbeat
  (let [state (alerts/create-alert-state)
        rules [(error-rule 0.5)]]
    (doseq [[id ok?] [["a" false] ["b" false] ["c" true]]]
      (alerts/evaluate-rules rules (done id ok?) state))
    (alerts/evaluate-rules rules (done "unknown" nil) state)
    (is (= 3 (count (:tool-outcomes @state))))
    (is (empty? (alerts/evaluate-rules rules {:event/type :agent/chunk} state)))
    (let [fired (alerts/evaluate-rules rules (heartbeat nil) state)]
      (is (= [:tool-error-rate] (mapv :alert/type fired)))
      (is (= 2 (get-in (first fired) [:alert/data :failure-count]))))))

(deftest alert-envelope-preserves-alert-timestamp
  (let [state (observer/create-alert-state)
        alert (first (observer/evaluate-rules [(phase-rule 1)] (heartbeat 2) state))
        event (observer/alert-fired nil "wf-1" alert)]
    (is (= :observer/alert-fired (:event/type event)))
    (is (= "wf-1" (:event/workflow-id event)))
    (is (= (:alert/timestamp alert) (:alert/timestamp event)))))

(deftest templates-and-empty-rules
  (let [state (alerts/create-alert-state)
        rule {:alert/id :template
              :alert/type :phase-gap
              :alert/threshold {:threshold-ms 1}
              :alert/message-template "gap={{gap-ms}} missing={{nope}}"}]
    (is (empty? (alerts/evaluate-rules [] (heartbeat 100) state)))
    (is (= "gap=2 missing="
           (:alert/message (first (alerts/evaluate-rules [rule] (heartbeat 2) state)))))))
