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

(ns ai.miniforge.anomaly.interface.predicate-test
  "Predicate behavior. `anomaly?` must say yes only for canonical
   anomaly maps, and never blow up on nil or arbitrary input."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest anomaly?-true-for-constructed-anomalies
  (testing "every anomaly produced by the constructor is recognized"
    (doseq [t anomaly/anomaly-types]
      (is (anomaly/anomaly? (anomaly/anomaly t "msg" {}))
          (str "expected anomaly? true for type " t)))))

(deftest anomaly?-false-for-nil-and-non-maps
  (testing "anomaly? returns false for nil and non-map values"
    (is (false? (anomaly/anomaly? nil)))
    (is (false? (anomaly/anomaly? "not a map")))
    (is (false? (anomaly/anomaly? 42)))
    (is (false? (anomaly/anomaly? :keyword)))
    (is (false? (anomaly/anomaly? [])))
    (is (false? (anomaly/anomaly? #{})))))

(deftest anomaly?-false-for-malformed-maps
  (testing "anomaly? rejects maps missing required keys"
    (is (false? (anomaly/anomaly? {})))
    (is (false? (anomaly/anomaly? {:anomaly/type :not-found})))
    (is (false? (anomaly/anomaly? {:anomaly/type :not-found
                                   :anomaly/message "x"})))
    (is (false? (anomaly/anomaly? {:anomaly/type :not-found
                                   :anomaly/message "x"
                                   :anomaly/data {}})))))

(deftest anomaly?-false-for-extra-keys
  (testing "the schema is closed; extra keys disqualify the map"
    (let [a (anomaly/anomaly :fault "boom" {})]
      (is (false? (anomaly/anomaly? (assoc a :something/else 1)))))))

(deftest anomaly?-false-for-bad-value-types
  (testing "anomaly? rejects maps whose values violate the schema"
    (is (false? (anomaly/anomaly? {:anomaly/type "string-not-keyword"
                                   :anomaly/message "m"
                                   :anomaly/data {}
                                   :anomaly/at (java.time.Instant/now)})))
    (is (false? (anomaly/anomaly? {:anomaly/type :fault
                                   :anomaly/message :keyword-not-string
                                   :anomaly/data {}
                                   :anomaly/at (java.time.Instant/now)})))
    (is (false? (anomaly/anomaly? {:anomaly/type :fault
                                   :anomaly/message "m"
                                   :anomaly/data "not a map"
                                   :anomaly/at (java.time.Instant/now)})))
    (is (false? (anomaly/anomaly? {:anomaly/type :fault
                                   :anomaly/message "m"
                                   :anomaly/data {}
                                   :anomaly/at "not an inst"})))))
