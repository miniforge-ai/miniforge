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

(ns ai.miniforge.phase-software-factory.plan-test
  "Tests for the plan phase — specifically the policy-pack wiring that
   was added so the planner agent sees the same compiled standards
   pack the rest of the phases do."
  (:require
   [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
   [ai.miniforge.phase-software-factory.plan :as plan]
   [ai.miniforge.phase.interface :as phase]
   [clojure.test :refer [deftest is testing]]))

(deftest build-planner-task-threads-behavior-addendum-test
  (testing "build-planner-task computes and attaches :task/behavior-addendum"
    (let [stub-addendum "\n\n## Policy Rules — Required Behaviors\n\n1. Plan rule body."]
      (with-redefs [phase/load-and-filter-behaviors
                    (fn [phase-kw _ctx]
                      (when (= :plan phase-kw) stub-addendum))
                    kb-helpers/inject-with-manifest
                    (fn [_store _role _tags]
                      {:formatted nil :manifest nil})]
        (let [{:keys [task]} (plan/build-planner-task
                              {:description "Add feature"
                               :title "Feature"
                               :intent "testing"
                               :constraints nil}
                              {:exploration/files []}
                              nil)]
          (is (= stub-addendum (:task/behavior-addendum task))
              "planner task must carry the phase-filtered addendum so create-planner can append it to the system prompt"))))))

(deftest build-planner-task-omits-behavior-addendum-when-filter-returns-nil-test
  (testing "no :task/behavior-addendum key when phase/load-and-filter-behaviors yields nil"
    (with-redefs [phase/load-and-filter-behaviors (fn [_phase _ctx] nil)
                  kb-helpers/inject-with-manifest
                  (fn [_store _role _tags]
                    {:formatted nil :manifest nil})]
      (let [{:keys [task]} (plan/build-planner-task
                            {:description "Add feature"
                             :title "Feature"
                             :intent "testing"
                             :constraints nil}
                            {:exploration/files []}
                            nil)]
        (is (not (contains? task :task/behavior-addendum))
            "no addendum key when filter yields nothing — avoids nil-valued task entries")))))
