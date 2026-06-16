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

(deftest success-metrics-drops-present-nil-cost-usd-test
  ;; The 2026-06-16 dogfood crashed the implement phase with a message-less
  ;; NPE: build-code-response wrote :cost-usd nil into metrics (the LLM turn
  ;; reported no cost), and the cost accumulator `(fnil + 0.0) cost` saw the
  ;; present nil — readers coalesce cost-usd with a default, but get/keyword-
  ;; with-default returns a present nil, not the default. Drop a present-nil
  ;; :cost-usd at the constructor so every reader's default/estimate applies.
  (testing "present-but-nil :cost-usd is dropped, not passed through"
    (let [m (metrics (response/success :out {:metrics {:tokens 5 :duration-ms 100 :cost-usd nil}}))]
      (is (not (contains? m :cost-usd))
          "a present-nil :cost-usd must not survive — readers fall back to their own default")
      (is (= 5 (:tokens m)))
      (is (= 100 (:duration-ms m)))))
  (testing "a real numeric :cost-usd is preserved"
    (let [m (metrics (response/success :out {:metrics {:tokens 5 :duration-ms 100 :cost-usd 0.42}}))]
      (is (= 0.42 (:cost-usd m)))))
  (testing "absent :cost-usd stays absent (optional)"
    (let [m (metrics (response/success :out {:metrics {:tokens 5 :duration-ms 100}}))]
      (is (not (contains? m :cost-usd)))))
  (testing "error path also drops a present-nil :cost-usd"
    (let [m (metrics (response/error "boom" {:metrics {:tokens 5 :duration-ms 100 :cost-usd nil}}))]
      (is (not (contains? m :cost-usd))))))

(deftest error-and-failure-metrics-never-nil-test
  (testing "error normalizes an explicit nil :tokens"
    (let [m (metrics (response/error "boom" {:metrics {:tokens nil :duration-ms nil}}))]
      (is (= 0 (:tokens m)))
      (is (= 0 (:duration-ms m)))))
  (testing "failure (delegates to error) normalizes an explicit nil :tokens"
    (let [m (metrics (response/failure "boom" {:metrics {:tokens nil}}))]
      (is (= 0 (:tokens m)))
      (is (number? (:duration-ms m))))))
