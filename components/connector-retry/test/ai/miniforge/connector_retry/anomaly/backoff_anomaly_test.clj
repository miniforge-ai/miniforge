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

(ns ai.miniforge.connector-retry.anomaly.backoff-anomaly-test
  "Coverage for `backoff/compute-delay` boundary escalation via
   `response/throw-anomaly!`. Unknown retry strategy →
   `:anomalies/unsupported`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-retry.backoff :as backoff])
  (:import (clojure.lang ExceptionInfo)))

(deftest compute-delay-unknown-strategy-throws-anomaly
  (testing "unknown :retry/strategy raises :anomalies/unsupported"
    (try
      (backoff/compute-delay {:retry/strategy :bogus
                              :retry/initial-delay-ms 100}
                             0)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Unknown retry strategy" (.getMessage e)))
        (is (= :anomalies/unsupported (:anomaly/category (ex-data e))))
        (is (= :bogus (:strategy (ex-data e))))))))

(deftest compute-delay-known-strategies-return-numbers
  (testing "known strategies return non-negative delays"
    (is (= 100 (backoff/compute-delay {:retry/strategy :fixed
                                       :retry/initial-delay-ms 100}
                                      3)))
    (is (number? (backoff/compute-delay {:retry/strategy :exponential
                                         :retry/initial-delay-ms 100}
                                        2)))))
