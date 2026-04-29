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

(ns ai.miniforge.cli.main.commands.run-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.run :as sut]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]))

(deftest run-spec-workflow-propagates-worktree-into-execution-opts-test
  (testing "--worktree becomes authoritative execution worktree metadata"
    (let [captured-opts (atom nil)]
      (with-redefs [display/print-info (fn [& _])
                    messages/t (fn
                                 ([k] (name k))
                                 ([k _] (name k)))
                    spec-parser/parse-spec-file (fn [_]
                                                  {:spec/title "Behavioral Verification"})
                    spec-parser/validate-spec (fn [_]
                                                {:valid? true})
                    workflow-runner/run-workflow-from-spec! (fn [_ opts]
                                                              (reset! captured-opts opts)
                                                              :ok)]
        (#'sut/run-spec-workflow "/tmp/behavioral.md" {:worktree "/tmp/custom-worktree"})
        (is (= {:worktree-path "/tmp/custom-worktree"}
               (:execution-opts @captured-opts)))))))

(deftest detect-input-type-and-markdown-spec-regression-test
  (testing "markdown-spec? recognizes markdown spec files"
    (is (true? (#'sut/markdown-spec? "/tmp/behavioral.md")))
    (is (true? (#'sut/markdown-spec? "/tmp/behavioral.markdown")))
    (is (false? (#'sut/markdown-spec? "/tmp/behavioral.yaml"))))

  (testing "detect-input-type classifies markdown and workflow inputs consistently"
    (let [spec-type (#'sut/detect-input-type {:spec/title "Behavioral Verification"})
          dag-type (#'sut/detect-input-type {:dag-id :behavioral-monitor})
          plan-type (#'sut/detect-input-type {:plan/id :behavioral-plan})]
      (is (= :spec spec-type))
      (is (= :dag dag-type))
      (is (= :plan plan-type)))))
