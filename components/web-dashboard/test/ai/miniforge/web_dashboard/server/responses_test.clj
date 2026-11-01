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
(ns ai.miniforge.web-dashboard.server.responses-test
  "JSON encoding of values cheshire cannot encode on its own.

   Every anomaly carries a `java.time.Instant` under `:anomaly/at`, so
   any failure result a handler returns as JSON contains one."
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.web-dashboard.server.handlers :as handlers]
   [ai.miniforge.web-dashboard.server.responses :as sut])
  (:import
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} parse-body
  [response]
  (json/parse-string (:body response) true))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} json-response-encodes-instant-test
  (testing "an Instant is written as its ISO-8601 string"
    (let [at (Instant/parse "2026-10-28T12:34:56.789Z")]
      (is (= {:at "2026-10-28T12:34:56.789Z"}
             (parse-body (sut/json-response {:at at})))))))

(deftest ^{:stratum 1} fleet-add-repo-failure-response-is-json-test
  (testing "an invalid repo slug returns the failure result as JSON, anomaly included"
    (let [response (handlers/handle-api-fleet-add-repo (atom {}) {:repo "not-a-repo-slug"})
          body     (parse-body response)]
      (is (= 200 (:status response)))
      (is (false? (:success body)))
      (is (string? (:error body)))
      (is (= "fault" (get-in body [:anomaly :anomaly/type])))
      (is (instance? Instant (Instant/parse (get-in body [:anomaly :anomaly/at])))))))
