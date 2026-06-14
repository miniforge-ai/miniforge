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

(ns ai.miniforge.response.builder-test
  "Boundary contract for the response builders: a constructed result's
   :metrics ALWAYS has non-nil numeric :tokens and :duration-ms, even when a
   caller passes a metrics map carrying an explicit nil. This is the boundary
   normalization that prevents message-less NPEs in downstream metric
   arithmetic (the 2026-06-14 n06 dogfood implement-leave crash)."
  (:require
   [ai.miniforge.response.interface :as response]
   [clojure.test :refer [deftest is testing]]))

(defn- metrics [r] (:metrics r))

(deftest success-metrics-never-nil-test
  (testing "explicit nil :tokens in the metrics map is normalized to 0, not passed through"
    (let [m (metrics (response/success :out {:metrics {:tokens nil :duration-ms nil}}))]
      (is (= 0 (:tokens m)))
      (is (= 0 (:duration-ms m)))))
  (testing "absent metrics → numeric defaults"
    (let [m (metrics (response/success :out))]
      (is (number? (:tokens m)))
      (is (number? (:duration-ms m)))))
  (testing "real values are preserved"
    (let [m (metrics (response/success :out {:metrics {:tokens 1500 :duration-ms 3000}}))]
      (is (= 1500 (:tokens m)))
      (is (= 3000 (:duration-ms m)))))
  (testing "top-level :tokens/:duration-ms opts still honored"
    (let [m (metrics (response/success :out {:tokens 42 :duration-ms 7}))]
      (is (= 42 (:tokens m)))
      (is (= 7 (:duration-ms m))))))

(deftest error-and-failure-metrics-never-nil-test
  (testing "error normalizes an explicit nil :tokens"
    (let [m (metrics (response/error "boom" {:metrics {:tokens nil :duration-ms nil}}))]
      (is (= 0 (:tokens m)))
      (is (= 0 (:duration-ms m)))))
  (testing "failure (delegates to error) normalizes an explicit nil :tokens"
    (let [m (metrics (response/failure "boom" {:metrics {:tokens nil}}))]
      (is (= 0 (:tokens m)))
      (is (number? (:duration-ms m))))))
