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

(ns ai.miniforge.anomaly.interface.validation-anomaly-test
  "validation-anomaly builds a canonical :invalid-input anomaly that
   carries the schema name, offending value, and explain data."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest validation-anomaly-is-a-canonical-invalid-input-anomaly
  (testing "result satisfies anomaly? and is typed :invalid-input"
    (let [a (anomaly/validation-anomaly "bad input" :career/example {:id nil} {:id ["required"]})]
      (is (anomaly/anomaly? a))
      (is (= :invalid-input (:anomaly/type a)))
      (is (= "bad input" (:anomaly/message a))))))

(deftest validation-anomaly-carries-schema-value-and-explain
  (testing "schema/value/explain are preserved under :anomaly/data"
    (let [explain {:id ["required"]}
          a (anomaly/validation-anomaly "bad" :career/example {:id nil} explain)]
      (is (= :career/example (get-in a [:anomaly/data :anomaly/schema])))
      (is (= {:id nil} (get-in a [:anomaly/data :anomaly/value])))
      (is (= explain (get-in a [:anomaly/data :anomaly/explain]))))))
