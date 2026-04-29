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

(ns ai.miniforge.anomaly.interface.constructor-test
  "Happy-path construction. The constructor must produce a map with
   the four canonical `:anomaly/*` keys, preserving the type, message,
   and data the caller passed."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest anomaly-returns-canonical-shape
  (testing "constructor populates all four canonical keys"
    (let [a (anomaly/anomaly :not-found "missing user" {:id 7})]
      (is (= :not-found (:anomaly/type a)))
      (is (= "missing user" (:anomaly/message a)))
      (is (= {:id 7} (:anomaly/data a)))
      (is (inst? (:anomaly/at a))))))

(deftest anomaly-result-is-a-plain-map
  (testing "anomaly is a plain Clojure map, not an exception"
    (let [a (anomaly/anomaly :fault "boom" {})]
      (is (map? a))
      (is (not (instance? Throwable a))))))

(deftest anomaly-keys-are-namespaced
  (testing "every key on the result is in the :anomaly namespace"
    (let [a (anomaly/anomaly :timeout "slow" {:ms 500})]
      (is (every? #(= "anomaly" (namespace %)) (keys a)))
      (is (= #{:anomaly/type :anomaly/message :anomaly/data :anomaly/at}
             (set (keys a)))))))
