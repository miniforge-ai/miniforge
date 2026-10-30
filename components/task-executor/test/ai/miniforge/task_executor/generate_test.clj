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
(ns ai.miniforge.task-executor.generate-test
  "create-generate-fn hands the implementer the task (with the formatted
   prompt as its description) and the caller's context, in that order."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.task-executor.generate :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} invoke-once
  "Call a generate-fn from create-generate-fn on `task` and `context` with
   the implementer and inner loop stubbed. Returns the [agent task context]
   agent/invoke received."
  [task context]
  (let [seen (atom nil)]
    (with-redefs [agent/create-implementer (constantly ::implementer)
                  agent/invoke (fn [a t ctx] (reset! seen [a t ctx]) {:artifact {:code/files []}})
                  loop/run-simple (fn [loop-task generate-fn ctx]
                                    (generate-fn loop-task ctx)
                                    {:artifact {:code/files []}})]
      ((sut/create-generate-fn ::backend) task context))
    @seen))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} implementer-gets-task-then-context-test
  (testing "the implementer's task carries the prompt; its context carries the worktree"
    (let [[implementer task ctx] (invoke-once
                                  {:task/id "t-1" :task/type :fix
                                   :description "Fix CI failures. Failed job logs: ERROR in (foo-test)"}
                                  {:worktree-path "/repo/checkout"})]
      (is (= ::implementer implementer))
      (is (str/includes? (:task/description task) "Failed job logs: ERROR in (foo-test)"))
      (is (str/includes? (:task/description task) "/repo/checkout"))
      (is (= "/repo/checkout" (:worktree-path ctx)))
      (is (= ::backend (:llm-backend ctx))))))
