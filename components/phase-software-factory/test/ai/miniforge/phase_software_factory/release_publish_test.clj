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
(ns ai.miniforge.phase-software-factory.release-publish-test
  (:require
   [ai.miniforge.phase-software-factory.release-publish :as release-publish]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} provenance-origin-is-the-run-lineage-test
  (let [origin (fn [ctx]
                 (get-in (release-publish/build-publish-context
                          (assoc ctx :execution/opts {:github-token "t"}) {:worktree-path "/wt"} nil)
                         [:provenance :origin]))]
    (testing "a DAG task: the parent's lineage from its input"
      (is (= "parent-origin" (origin {:execution/id "task-run"
                                      :execution/origin-id "task-run"
                                      :execution/input {:workflow/origin-id "parent-origin"}}))))
    (testing "a resumed attempt: the run it continues"
      (is (= "first-run" (origin {:execution/id "attempt-2" :execution/origin-id "first-run"}))))
    (testing "a context with no lineage recorded: its own id"
      (is (= "run" (origin {:execution/id "run"}))))))
