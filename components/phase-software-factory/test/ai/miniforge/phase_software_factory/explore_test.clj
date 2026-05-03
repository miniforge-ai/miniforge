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

(ns ai.miniforge.phase-software-factory.explore-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase-software-factory.explore :as sut]))

(deftest enter-explore-uses-context-files-in-scope-test
  (testing "explore reads files-in-scope from nested execution input context"
    (let [captured (atom nil)
          loaded-files [{:path "components/workflow/src/foo.clj"
                         :content "(ns ai.miniforge.workflow.foo)"}]]
      (with-redefs [phase/load-files-in-scope
                    (fn [worktree-path files-in-scope opts]
                      (reset! captured {:worktree-path worktree-path
                                        :files-in-scope files-in-scope
                                        :opts opts})
                      loaded-files)
                    phase/enter-context
                    (fn [_ctx _phase _agent _gates _budget _start-time result]
                      result)]
        (let [result (sut/enter-explore
                      {:execution/input
                       {:context {:files-in-scope ["components/workflow/src/foo.clj"]}}
                       :execution/worktree-path "/tmp/worktree"})]
          (is (= "/tmp/worktree" (:worktree-path @captured)))
          (is (= ["components/workflow/src/foo.clj"] (:files-in-scope @captured)))
          (is (= 1 (get-in result [:output :exploration/file-count]))))))))
