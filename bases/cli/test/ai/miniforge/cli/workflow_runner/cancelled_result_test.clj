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
(ns ai.miniforge.cli.workflow-runner.cancelled-result-test
  "The CLI reports a :cancelled run as cancelled: its terminal event, its
   summary line and its spec's kanban folder."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display-summary-lines :as lines]
   [ai.miniforge.cli.workflow-runner.lifecycle :as lifecycle]
   [ai.miniforge.cli.workflow-runner.spec-kanban :as kanban]
   [ai.miniforge.event-stream.interface :as es]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private cancelled-result
  {:execution/status :cancelled
   :execution/cancellation {:cancel/requested-by "op@example.invalid" :cancel/source :cli}
   :execution/metrics {:duration-ms 7}})

(deftest ^{:stratum 0} a-cancelled-run-summary-says-cancelled
  (is (str/includes? (lines/status-line :cancelled)
                     (messages/t :workflow-runner/summary-cancelled)))
  (is (str/includes? (lines/status-line :failed)
                     (messages/t :workflow-runner/summary-failure))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-cancelled-event-the-stream-refuses-publishes-a-failure-instead
  (let [stream (es/create-event-stream {:sinks []})]
    (lifecycle/publish-completion-event
     stream (random-uuid) (assoc-in cancelled-result [:execution/metrics :duration-ms] -1))
    (is (= [:workflow/failed] (mapv :event/type (es/get-events stream)))
        "the run still gets a terminal event")))

(deftest ^{:stratum 1} a-cancelled-run-publishes-workflow-cancelled
  (let [stream (es/create-event-stream {:sinks []})
        workflow-id (random-uuid)]
    (lifecycle/publish-completion-event stream workflow-id cancelled-result)
    (let [[event & more] (es/get-events stream)]
      (is (nil? more))
      (is (= :workflow/cancelled (:event/type event)))
      (is (= "op@example.invalid" (:cancel/requested-by event)))
      (is (= 7 (:workflow/duration-ms event))))))

(deftest ^{:stratum 1} a-cancelled-run-spec-stays-in-progress
  (is (nil? (#'kanban/completion-folder cancelled-result)))
  (is (= :failed (#'kanban/completion-folder {:execution/status :failed})))
  (is (= :done (#'kanban/completion-folder {:execution/status :completed}))))

(comment ::cancelled-result)
