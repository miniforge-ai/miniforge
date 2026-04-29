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

(ns ai.miniforge.connector.validation.require-handle-happy-path-test
  "Happy-path coverage for `connector/require-handle`: known handles
   surface their state unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.connector.interface :as connector]))

(deftest require-handle-returns-state-on-hit
  (testing "known handle returns its stored state"
    (let [store (connector/create-handle-registry)]
      (connector/store-handle! store "h-1" {:foo :bar})
      (is (= {:foo :bar} (connector/require-handle store "h-1"))))))

(deftest require-handle-returns-state-not-anomaly
  (testing "successful lookup is not an anomaly"
    (let [store (connector/create-handle-registry)]
      (connector/store-handle! store "h-1" {:foo :bar})
      (is (not (anomaly/anomaly? (connector/require-handle store "h-1")))))))

(deftest require-handle-supports-opts-passthrough
  (testing "opts map is accepted on the happy path without affecting result"
    (let [store (connector/create-handle-registry)]
      (connector/store-handle! store "h-1" {:foo :bar})
      (is (= {:foo :bar}
             (connector/require-handle store "h-1" {:connector :jira}))))))
