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
(ns ai.miniforge.agent.implementer-task-sections-test
  "task-sections is the one predicate list behind both prompt rendering
   and the :implementer/prompt-sections telemetry — so the log can only
   claim what task->text actually rendered."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.implementer :as implementer]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} task-sections-mirror-what-renders-test
  (testing "an EMPTY :task/gate-failures is neither rendered nor claimed"
    (let [task {:task/description "x" :task/gate-failures []}]
      (is (= [] (implementer/task-sections task)))
      (is (not (str/includes? (implementer/task->text task) "Gate denial")))))
  (testing "a non-empty denial is both rendered and claimed, in render order"
    (let [task {:task/description "x"
                :task/verify-failures {:test-results {:fail-count 1}}
                :task/gate-failures [{:gate :g :errors [{:message "m"}]}]}]
      (is (= [:task/gate-failures :task/verify-failures]
             (implementer/task-sections task)))
      (is (str/includes? (implementer/task->text task) "Gate denial")))))
