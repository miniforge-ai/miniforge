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

(ns ai.miniforge.anomaly.interface.data-default-test
  "`:anomaly/data` is required by the schema, so the constructor
   must coerce nil into an empty map. Callers should never have to
   type `{}` just to satisfy the shape."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest nil-data-becomes-empty-map
  (testing "nil :anomaly/data is normalized to {}"
    (let [a (anomaly/anomaly :fault "boom" nil)]
      (is (= {} (:anomaly/data a)))
      (is (anomaly/anomaly? a)))))

(deftest empty-map-data-stays-empty
  (testing "explicit empty map round-trips unchanged"
    (let [a (anomaly/anomaly :fault "boom" {})]
      (is (= {} (:anomaly/data a))))))

(deftest non-empty-data-is-preserved-verbatim
  (testing "non-empty data maps survive untouched"
    (let [d {:request-id "abc" :attempt 3 :nested {:k :v}}
          a (anomaly/anomaly :timeout "slow" d)]
      (is (= d (:anomaly/data a))))))
