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

(ns ai.miniforge.anomaly.interface.sub-anomaly-test
  "sub-anomaly attaches a domain :anomaly/subtype to an otherwise
   canonical anomaly; the result still validates, and `subtype` reads
   it back (nil for generic anomalies)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.anomaly.interface :as anomaly]))

(deftest sub-anomaly-is-canonical-with-subtype
  (testing "result satisfies anomaly?, keeps the generic type, carries the subtype"
    (let [a (anomaly/sub-anomaly :invalid-input :gate/validation-failed "gate rejected" {:gate "lint"})]
      (is (anomaly/anomaly? a))
      (is (= :invalid-input (:anomaly/type a)))
      (is (= :gate/validation-failed (:anomaly/subtype a)))
      (is (= "lint" (get-in a [:anomaly/data :gate]))))))

(deftest subtype-reads-domain-classification
  (testing "subtype returns the keyword, or nil for a generic anomaly"
    (is (= :agent/tool-loop
           (anomaly/subtype (anomaly/sub-anomaly :fault :agent/tool-loop "loop" {}))))
    (is (nil? (anomaly/subtype (anomaly/anomaly :fault "plain" {}))))))

(deftest generic-anomaly-omits-subtype-key
  (testing "a plain anomaly has no :anomaly/subtype key at all (closed schema stays valid)"
    (let [a (anomaly/anomaly :fault "plain" {})]
      (is (not (contains? a :anomaly/subtype)))
      (is (anomaly/anomaly? a)))))

(deftest subtype-rejects-non-keyword
  (testing "a non-keyword subtype violates the schema (anomaly? is false)"
    (is (false? (anomaly/anomaly? (assoc (anomaly/anomaly :fault "x" {})
                                         :anomaly/subtype "not-a-keyword"))))))
