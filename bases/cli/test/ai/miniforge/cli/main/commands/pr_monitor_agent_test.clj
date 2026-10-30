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
(ns ai.miniforge.cli.main.commands.pr-monitor-agent-test
  "The PR monitor's generate-fn answers both ways the monitor calls it,
   and --trusted-authors parses to a set of logins."
  (:require
   [ai.miniforge.cli.main.commands.pr-monitor-agent :as sut]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.task-executor.interface :as task-executor]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} echo-fix-fn
  "task-executor/create-generate-fn stub whose generate-fn echoes the
   task and context it was called with as the artifact."
  [& _]
  (fn [task ctx] {:artifact {:task task :ctx ctx} :tokens 1}))

(deftest ^{:stratum 0} parse-trusted-authors-test
  (testing "comma-separated logins become a set; blanks and repeats drop"
    (is (= #{"jane" "bob"} (sut/parse-trusted-authors "jane, bob,,jane"))))
  (testing "an absent flag trusts no extra logins"
    (is (= #{} (sut/parse-trusted-authors nil)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} prompt-arity-returns-model-text-test
  (testing "one argument: the monitor gets the model's text, in one turn"
    (let [seen (atom nil)
          client (llm/mock-client {:output "change-request"})]
      (with-redefs [task-executor/create-generate-fn echo-fix-fn
                    llm/chat (fn [c prompt opts]
                               (reset! seen opts)
                               (llm/complete c (assoc opts :prompt prompt)))]
        (is (= "change-request" ((sut/monitor-generate-fn client) "classify this")))
        (is (= sut/text-reply-max-turns (:max-turns @seen)))))))

(deftest ^{:stratum 1} prompt-arity-returns-nil-on-failure-test
  (testing "a failed model call yields nil, which the classifier treats as no answer"
    (let [client (llm/mock-client {:output "boom" :exit 1})]
      (with-redefs [task-executor/create-generate-fn echo-fix-fn]
        (is (nil? ((sut/monitor-generate-fn client) "classify this")))))))

(deftest ^{:stratum 1} task-arity-runs-the-implementer-test
  (testing "two arguments: the fix task goes to the task-executor generate-fn"
    (with-redefs [task-executor/create-generate-fn echo-fix-fn]
      (let [task {:task/id "t" :task/type :fix :description "Address review feedback"}
            result ((sut/monitor-generate-fn (llm/mock-client {})) task {:worktree-path "/repo"})]
        (is (= task (get-in result [:artifact :task])))
        (is (= "/repo" (get-in result [:artifact :ctx :worktree-path])))))))
