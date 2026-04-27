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

(ns ai.miniforge.anomaly.interface.schema-test
  "Direct malli validation against the re-exported `Anomaly` schema.
   Distinct from the predicate tests because callers in other
   miniforge repos may want to validate hand-built maps without
   going through the `anomaly?` helper."
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest schema-accepts-constructed-shape
  (testing "the re-exported Anomaly schema validates constructor output"
    (let [a (anomaly/anomaly :invalid-input "bad arg" {:arg :x})]
      (is (m/validate anomaly/Anomaly a)))))

(deftest schema-accepts-hand-built-shape
  (testing "any map matching the four canonical keys validates"
    (let [a {:anomaly/type    :unauthorized
             :anomaly/message "no token"
             :anomaly/data    {:user "alice"}
             :anomaly/at      (java.time.Instant/now)}]
      (is (m/validate anomaly/Anomaly a)))))

(deftest schema-rejects-missing-keys
  (testing "the Anomaly schema requires every canonical key"
    (is (not (m/validate anomaly/Anomaly {})))
    (is (not (m/validate anomaly/Anomaly
                         {:anomaly/type :fault
                          :anomaly/message "m"
                          :anomaly/data {}})))))

(deftest schema-rejects-wrong-types
  (testing "the Anomaly schema enforces value shapes"
    (is (not (m/validate anomaly/Anomaly
                         {:anomaly/type    :fault
                          :anomaly/message 42
                          :anomaly/data    {}
                          :anomaly/at      (java.time.Instant/now)})))
    (is (not (m/validate anomaly/Anomaly
                         {:anomaly/type    :fault
                          :anomaly/message "m"
                          :anomaly/data    [:not :a :map]
                          :anomaly/at      (java.time.Instant/now)})))))

(deftest schema-rejects-extra-keys
  (testing "the schema is closed; extra keys fail validation"
    (let [a (anomaly/anomaly :fault "boom" {})]
      (is (not (m/validate anomaly/Anomaly (assoc a :extra/key 1)))))))
