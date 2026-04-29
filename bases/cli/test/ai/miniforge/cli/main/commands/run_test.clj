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
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]))

(deftest run-spec-workflow-propagates-worktree-into-execution-opts-test
  (testing "--worktree is materialized and passed as authoritative execution metadata"
    (let [captured-opts (atom nil)]
      (with-redefs [display/print-info (fn [& _])
                    messages/t (fn
                                 ([k] (name k))
                                 ([k _] (name k)))
                    worktree/materialize-execution-worktree!
                    (fn [_source-dir requested-worktree]
                      (str requested-worktree "-materialized"))
                    spec-parser/parse-spec-file (fn [_]
                                                  {:spec/title "Behavioral Verification"})
                    spec-parser/validate-spec (fn [_]
                                                {:valid? true})
                    workflow-runner/run-workflow-from-spec! (fn [_ opts]
                                                              (reset! captured-opts opts)
                                                              :ok)]
        (#'sut/run-spec-workflow "/tmp/behavioral.md" {:worktree "/tmp/custom-worktree"})
        (is (= {:worktree-path "/tmp/custom-worktree-materialized"}
               (:execution-opts @captured-opts)))))))

(deftest detect-input-type-test
  (testing "spec-shaped inputs are classified as specs"
    (is (= :spec (sut/detect-input-type {:spec/title "Behavioral Verification"}))))
  (testing "dag-shaped inputs are classified as dags"
    (is (= :dag (sut/detect-input-type {:dag-id (random-uuid)}))))
  (testing "plan-shaped inputs are classified as plans"
    (is (= :plan (sut/detect-input-type {:plan/id (random-uuid)}))))
  (testing "unrecognized shapes return nil"
    (is (nil? (sut/detect-input-type {:foo :bar})))))

(deftest markdown-spec?-test
  (testing "markdown extensions are recognized"
    (is (true? (sut/markdown-spec? "spec.md")))
    (is (true? (sut/markdown-spec? "spec.markdown"))))
  (testing "non-markdown extensions are rejected"
    (is (false? (sut/markdown-spec? "spec.edn")))
    (is (false? (sut/markdown-spec? "spec.json")))))
