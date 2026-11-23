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
(ns ai.miniforge.deliberation-harness.fixtures-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.fixtures :as fixtures]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private minimum-tasks
  "The package ships at least the two documented synthetic tasks."
  2)

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-package-discriminates
  (let [package (fixtures/tasks)]
    (is (<= minimum-tasks (count package)))
    (is (empty? (fixtures/fixture-defects package)) "every reference passes and every distractor fails")))

(deftest ^{:stratum 1} success-needs-every-observation
  (let [task (first (fixtures/tasks))]
    (is (zero? (:success (fixtures/score task (:reference task) []))))
    (is (= 1 (:success (fixtures/score task (:reference task) (keys (:experiments task))))))))

(deftest ^{:stratum 1} the-public-view-hides-the-sealed-answer
  (let [view (fixtures/public-task (first (fixtures/tasks)))]
    (is (empty? (select-keys view [:reference :distractors :experiments])))))

(deftest ^{:stratum 1} unscorable-input-is-an-anomaly
  ;; No reference (empty or absent), experiments not a map, inspected ids not a collection.
  (doseq [[task solution inspected] [[{:id "t" :reference {}} {} []] [{:id "t"} {} []]
                                     [{:id "t" :reference {:a 1} :experiments [1 2]} {:a 1} []]
                                     [(first (fixtures/tasks)) {} 5]]]
    (is (anomaly/anomaly? (fixtures/score task solution inspected)) (pr-str task))))

(deftest ^{:stratum 1} an-omitted-choice-never-scores
  (is (= {:a false} (:checks (fixtures/score {:id "t" :reference {:a nil}} {} [])))))
