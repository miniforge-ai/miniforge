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

(ns ai.miniforge.cli.workflow-runner.context-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.cli.workflow-runner.context :as sut]
   [ai.miniforge.event-stream.interface :as es]))

(deftest create-workflow-context-prefers-explicit-execution-worktree-test
  (testing "execution-opts worktree-path overrides discovered repo root"
    (with-redefs [worktree/worktree-root (constantly "/tmp/repo-root")
                  es/create-streaming-callback (fn [& _] nil)
                  es/workflow-started (fn [& _] {})
                  es/publish! (fn [& _] nil)]
      (let [context (sut/create-workflow-context
                     {:callbacks {}
                      :event-stream :stream
                      :workflow-id (random-uuid)
                      :workflow-type :canonical-sdlc
                      :workflow-version "1.0.0"
                      :source-dir "/tmp/repo-root/work"
                      :execution-opts {:worktree-path "/tmp/execution-worktree"}})]
        (is (= "/tmp/execution-worktree" (:worktree-path context)))
        (is (= "/tmp/repo-root" (:source-root context)))
        (is (= {:worktree-path "/tmp/execution-worktree"}
               (:execution/opts context)))))))

(deftest create-workflow-context-falls-back-to-discovered-worktree-test
  (testing "repo root is used only when no explicit execution worktree is present"
    (with-redefs [worktree/worktree-root (constantly "/tmp/repo-root")
                  es/create-streaming-callback (fn [& _] nil)
                  es/workflow-started (fn [& _] {})
                  es/publish! (fn [& _] nil)]
      (let [context (sut/create-workflow-context
                     {:callbacks {}
                      :event-stream :stream
                      :workflow-id (random-uuid)
                      :workflow-type :canonical-sdlc
                      :workflow-version "1.0.0"})]
        (is (= "/tmp/repo-root" (:worktree-path context)))
        (is (= "/tmp/repo-root" (:source-root context)))))))
