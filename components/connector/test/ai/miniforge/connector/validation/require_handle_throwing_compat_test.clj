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

(ns ai.miniforge.connector.validation.require-handle-throwing-compat-test
  "Backward-compat coverage for the deprecated throwing
   `connector/require-handle!`. Per-connector callsites still call into a
   throwing helper today; the shape contract here matches what those
   per-connector helpers currently rely on."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector.interface :as connector]))

(deftest require-handle-bang-returns-state-on-hit
  (testing "deprecated thrower returns the stored state on success"
    (let [store (connector/create-handle-registry)]
      (connector/store-handle! store "h-1" {:foo :bar})
      (is (= {:foo :bar} (connector/require-handle! store "h-1"))))))

(deftest require-handle-bang-throws-on-miss
  (testing "deprecated thrower throws ExceptionInfo when handle is unknown"
    (let [store (connector/create-handle-registry)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (connector/require-handle! store "missing"))))))

(deftest require-handle-bang-ex-data-carries-handle
  (testing "thrown ex-info data carries the missing handle"
    (let [store (connector/create-handle-registry)]
      (try
        (connector/require-handle! store "missing")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "missing" (:handle (ex-data e)))))))))
