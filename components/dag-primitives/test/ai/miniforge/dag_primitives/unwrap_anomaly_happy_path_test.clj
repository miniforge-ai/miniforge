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

(ns ai.miniforge.dag-primitives.unwrap-anomaly-happy-path-test
  "Happy-path coverage for `dag-primitives/unwrap-anomaly`: ok results
   surface their wrapped data unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.dag-primitives.interface :as dp]))

(deftest unwrap-anomaly-returns-data-on-ok
  (testing "ok result surfaces its data"
    (is (= 42 (dp/unwrap-anomaly (dp/ok 42))))))

(deftest unwrap-anomaly-preserves-nested-data
  (testing "complex data structures pass through unchanged"
    (let [payload {:nodes [:a :b :c] :edges #{[:a :b]}}]
      (is (= payload (dp/unwrap-anomaly (dp/ok payload)))))))

(deftest unwrap-anomaly-ok-result-not-an-anomaly
  (testing "ok unwrap result is not flagged as anomaly"
    (is (not (anomaly/anomaly? (dp/unwrap-anomaly (dp/ok :anything)))))))
