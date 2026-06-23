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

(ns ai.miniforge.reporting.core-test
  "Unit tests for reporting core helpers."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.reporting.core :as core]
   [ai.miniforge.logging.interface :as log]))

(deftest test-safe-get-returns-value-on-success
  (testing "safe-get returns the fn result when no exception is thrown"
    (is (= 42 (core/safe-get (fn [] 42))))
    (is (= :ok (core/safe-get (constantly :ok))))))

(deftest test-safe-get-returns-nil-on-exception
  (testing "safe-get without logger returns nil when fn throws"
    (is (nil? (core/safe-get (fn [] (throw (Exception. "boom"))))))))

(deftest test-safe-get-with-logger-returns-nil-on-exception
  (testing "safe-get with logger returns nil when fn throws"
    (let [[logger _] (log/collecting-logger)]
      (is (nil? (core/safe-get logger (fn [] (throw (Exception. "boom")))))))))

(deftest test-safe-get-with-logger-emits-warn-on-exception
  (testing "safe-get emits :reporting/safe-get-error at WARN when logger is provided"
    (let [[logger entries] (log/collecting-logger)]
      (core/safe-get logger (fn [] (throw (Exception. "boom"))))
      (is (some #(and (= :reporting/safe-get-error (:log/event %))
                      (= :warn (:log/level %)))
                @entries)))))

(deftest test-safe-get-with-logger-returns-value-on-success
  (testing "safe-get with logger returns value and does not log when fn succeeds"
    (let [[logger entries] (log/collecting-logger)]
      (is (= 42 (core/safe-get logger (fn [] 42))))
      (is (empty? @entries)))))

(deftest test-safe-get-nil-logger-is-safe
  (testing "safe-get with nil logger behaves like no-logger variant"
    (is (nil? (core/safe-get nil (fn [] (throw (Exception. "boom"))))))
    (is (= 42 (core/safe-get nil (fn [] 42))))))
