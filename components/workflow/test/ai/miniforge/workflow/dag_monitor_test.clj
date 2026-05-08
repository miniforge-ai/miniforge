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

(ns ai.miniforge.workflow.dag-monitor-test
  "Tests for ai.miniforge.workflow.dag-monitor — currently focused on
   the spec §6.4 :resolve-fn injection gate added in Stage 3d. Other
   monitor-cycle behaviour is exercised through the broader
   monitoring-test integration suite."
  (:require
   [ai.miniforge.workflow.dag-monitor :as sut]
   [clojure.test :refer [deftest is testing]]))

(def ^:private create-pr-controllers
  (var-get #'sut/create-pr-controllers))

(deftest resolve-fn-only-injected-when-llm-backend-present-test
  (testing "PR #805 review fix: dag-monitor must NOT inject a
            resolve-fn when context lacks :llm-backend. With no
            backend, merge-resolution would fall back to its no-op
            stub agent and deterministically return an unresolvable
            anomaly — the lifecycle would attempt resolution every
            cycle for no benefit. Skipping injection lets the
            conflict-resolution dispatch decline cleanly and the
            existing rebase / not-ready path handles the PR.
            (e.g. phase-software-factory's pr_monitor calls
            monitor-dag-prs without :llm-backend.)"
    (let [pr-infos [{:pr-number 1 :task-id (random-uuid)}]
          controllers (create-pr-controllers
                       pr-infos
                       {:worktree-path "/tmp"})]
      (is (nil? (:resolve-fn @(get controllers 1)))
          ":resolve-fn must be absent when :llm-backend is absent"))))

(deftest resolve-fn-injected-when-llm-backend-present-test
  (testing "Companion to the above: when :llm-backend IS on context,
            dag-monitor wires the resolve-fn closure so the §6.4
            hook fires end-to-end. Closure construction itself
            doesn't validate the backend, so we just confirm the
            controller carries a callable :resolve-fn."
    (let [pr-infos [{:pr-number 2 :task-id (random-uuid)}]
          controllers (create-pr-controllers
                       pr-infos
                       {:worktree-path "/tmp"
                        :llm-backend ::mock-backend})]
      (is (fn? (:resolve-fn @(get controllers 2)))
          "controller carries the resolve-fn closure when backend
           was supplied — closure body invokes merge-resolution/
           resolve-conflict! at call time"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.dag-monitor-test)

  :leave-this-here)
