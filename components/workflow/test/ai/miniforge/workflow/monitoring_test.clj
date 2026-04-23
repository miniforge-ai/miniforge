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

(ns ai.miniforge.workflow.monitoring-test
  (:require
   [ai.miniforge.agent.interface.supervision :as supervision]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [clojure.test :refer [deftest is testing]]))

(deftest create-supervisors-defaults-test
  (testing "create-supervisors falls back to the default progress monitor"
    (let [captured-config (atom nil)
          supervisors (with-redefs [supervision/create-progress-monitor-agent
                                    (fn [config]
                                      (reset! captured-config config)
                                      :progress-monitor)]
                        (monitoring/create-supervisors {:workflow/id :test}))]
      (is (= [:progress-monitor] supervisors))
      (is (= monitoring/default-progress-monitor-config
             @captured-config)))))

(deftest create-supervisors-merges-configured-progress-monitor-test
  (testing "create-supervisors merges workflow overrides into the factory config"
    (let [captured-config (atom nil)
          workflow {:workflow/id :test
                    :workflow/meta-agents
                    [{:id :progress-monitor
                      :config {:max-total-ms 900000}}]}
          supervisors (with-redefs [supervision/create-progress-monitor-agent
                                    (fn [config]
                                      (reset! captured-config config)
                                      :progress-monitor)]
                        (monitoring/create-supervisors workflow))]
      (is (= [:progress-monitor] supervisors))
      (is (= (assoc monitoring/default-progress-monitor-config
                    :max-total-ms 900000)
             @captured-config)))))

(deftest create-supervisors-invalid-id-test
  (testing "create-supervisors fails fast on unsupported supervisor ids"
    (let [workflow {:workflow/id :test
                    :workflow/meta-agents [{:id :unknown-supervisor}]}
          result (try
                   (monitoring/create-supervisors workflow)
                   nil
                   (catch clojure.lang.ExceptionInfo ex
                     ex))]
      (is (instance? clojure.lang.ExceptionInfo result))
      (let [data (ex-data result)]
        (is (= :anomalies.workflow/invalid-supervisor
               (:anomaly/category data)))
        (is (= :unknown-supervisor
               (:supervisor/id data)))))))

(deftest handle-supervision-halt-builds-canonical-payloads-test
  (testing "handle-supervision-halt reuses canonical halt payload builders"
    (let [ctx {:execution/errors []
               :execution/response-chain (response/create :workflow)}
          supervision-result {:halt-reason "workflow stalled"
                              :halting-agent :progress-monitor
                              :checks [{:status :halt
                                        :data {:elapsed-ms 120000}}]}
          result (monitoring/handle-supervision-halt ctx
                                                     supervision-result
                                                     identity)]
      (is (= {:type :supervision-halt
              :supervisor :progress-monitor
              :message "workflow stalled"
              :data {:elapsed-ms 120000}}
             (last (:execution/errors result))))
      (is (= :anomalies.workflow/halted-by-supervision
             (response/last-anomaly (:execution/response-chain result))))
      (is (= {:supervisor :progress-monitor
              :reason "workflow stalled"
              :checks [{:status :halt
                        :data {:elapsed-ms 120000}}]}
             (response/last-response (:execution/response-chain result)))))))
