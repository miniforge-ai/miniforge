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

(ns ai.miniforge.cli.workflow-runner.preflight-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]
   [ai.miniforge.cli.workflow-runner :as sut]))

(defn- temp-repo-root []
  (let [root (str (fs/create-temp-dir {:prefix "mf-preflight-"}))]
    (fs/create-dirs (fs/path root ".git"))
    root))

(deftest assert-runtime-alignment-rejects-worktree-mismatch-test
  (testing "explicit execution worktree must survive into runtime context"
    (let [source-root (temp-repo-root)
          source-dir (str (fs/path source-root "work"))]
      (try+
        (#'sut/assert-runtime-alignment!
         {:spec/source-dir source-dir}
         {:source-root source-root
          :worktree-path "/tmp/runtime-worktree"
          :execution/opts {:worktree-path "/tmp/expected-worktree"}})
        (is false "expected execution worktree mismatch anomaly")
        (catch [:anomaly/category :anomalies/incorrect]
               {:keys [expected-worktree actual-worktree]}
          (is (= "/tmp/expected-worktree" expected-worktree))
          (is (= "/tmp/runtime-worktree" actual-worktree)))))))

(deftest assert-runtime-alignment-rejects-source-dir-outside-root-test
  (testing "spec source directory must stay under the resolved source root"
    (let [source-root (temp-repo-root)]
      (try+
        (#'sut/assert-runtime-alignment!
         {:spec/source-dir "/tmp/outside-spec-root"}
         {:source-root source-root
          :worktree-path "/tmp/runtime-worktree"
          :execution/opts {:worktree-path "/tmp/runtime-worktree"}})
        (is false "expected source-dir alignment anomaly")
        (catch [:anomaly/category :anomalies/incorrect]
               {:keys [source-dir]}
          (is (= "/tmp/outside-spec-root" source-dir)))))))

(deftest print-runtime-provenance-includes-startup-warnings-test
  (testing "startup provenance prints upstream and source checkout warnings"
    (let [output (with-out-str
                   (#'sut/print-runtime-provenance!
                    false
                    {:source-root "/tmp/source-root"
                     :worktree-path "/tmp/runtime-worktree"
                     :git-branch "HEAD"
                     :git-commit "abc1234"
                     :git-upstream "origin/main"
                     :git-detached? true
                     :git-dirty? true}))]
      (is (str/includes? output "Source: /tmp/source-root"))
      (is (str/includes? output "Worktree: /tmp/runtime-worktree"))
      (is (str/includes? output "Upstream: origin/main"))
      (is (str/includes? output "detached HEAD"))
      (is (str/includes? output "uncommitted changes")))))
