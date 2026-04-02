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

(ns ai.miniforge.phase.deploy.validate-test
  (:require [ai.miniforge.phase.deploy.validate :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest resolve-validate-config-test
  (testing "input overrides phase config and defaults are normalized once"
    (let [resolved (#'sut/resolve-validate-config
                    {:phase-config {:health-endpoints ["http://cfg/health"]
                                    :smoke-commands ["echo cfg"]
                                    :retry-delay-ms 1000}
                     :execution/input {:smoke-commands ["echo input"]}})]
      (is (= ["http://cfg/health"] (:health-endpoints resolved)))
      (is (= ["echo input"] (:smoke-commands resolved)))
      (is (= 3 (:retries resolved)))
      (is (= 1000 (:retry-delay-ms resolved))))))

(deftest check-health-endpoints-retries-until-pass-test
  (testing "health checks retry until a passing result is returned"
    (let [attempts (atom 0)]
      (with-redefs [sut/http-health-check
                    (fn [_url]
                      (swap! attempts inc)
                      (if (= 2 @attempts)
                        {:passed? true :url "http://svc/health" :status-code 200 :duration-ms 10}
                        {:passed? false :url "http://svc/health" :status-code 503 :duration-ms 10}))]
        (let [result (#'sut/check-health-endpoints ["http://svc/health"] :retries 3 :retry-delay 0)]
          (is (true? (:passed? result)))
          (is (= 2 (:attempts (first (:results result)))))
          (is (= 2 @attempts)))))))
