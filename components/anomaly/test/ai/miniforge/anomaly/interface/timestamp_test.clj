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

(ns ai.miniforge.anomaly.interface.timestamp-test
  "`:anomaly/at` is populated at construction with the current
   instant. We bracket the call with `Instant/now` and assert that
   the recorded time falls within the bracket."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest timestamp-is-an-instant
  (testing ":anomaly/at is a java.time.Instant"
    (let [a (anomaly/anomaly :fault "boom" {})]
      (is (instance? java.time.Instant (:anomaly/at a))))))

(deftest timestamp-is-recent
  (testing ":anomaly/at falls between two bracketing now() calls"
    (let [before (java.time.Instant/now)
          a      (anomaly/anomaly :fault "boom" {})
          after  (java.time.Instant/now)
          at     (:anomaly/at a)]
      (is (not (.isBefore at before)))
      (is (not (.isAfter at after))))))

(deftest each-anomaly-gets-its-own-timestamp
  (testing "two anomalies built in sequence have monotonic timestamps"
    (let [a1 (anomaly/anomaly :fault "first" {})
          _  (Thread/sleep 1)
          a2 (anomaly/anomaly :fault "second" {})]
      (is (not (.isAfter (:anomaly/at a1)
                         (:anomaly/at a2)))))))
