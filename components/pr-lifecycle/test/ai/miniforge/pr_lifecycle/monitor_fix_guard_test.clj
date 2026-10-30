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
(ns ai.miniforge.pr-lifecycle.monitor-fix-guard-test
  "A change-request fix starts only with a generate-fn and the PR's branch
   checked out in the monitor's worktree."
  (:require
   [ai.miniforge.pr-lifecycle.monitor-fix-guard :as sut]
   [ai.miniforge.pr-lifecycle.monitor-handlers :as handlers]
   [ai.miniforge.pr-lifecycle.monitor-state :as state]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} config
  "Monitor config with a generate-fn that records being called in `calls`."
  [calls]
  {:worktree-path "/repo" :generate-fn (fn [& _] (swap! calls inc) nil)})

(deftest ^{:stratum 0} checked-out-branch-reads-a-real-checkout-test
  (testing "branch name and head commit of a git checkout"
    (let [dir (str (fs/create-temp-dir))
          git #(apply process/sh {:dir dir} "git" %&)]
      (try
        (git "init" "-q" "-b" "feat/x")
        (git "-c" "user.email=t@example.com" "-c" "user.name=t" "-c" "commit.gpgsign=false"
             "commit" "-q" "--allow-empty" "-m" "init")
        (is (= {:branch "feat/x" :sha (.trim ^String (:out (git "rev-parse" "HEAD")))}
               (sut/checked-out-branch dir)))
        (finally (fs/delete-tree dir))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} fix-refusal-test
  (let [calls (atom 0)]
    (testing "no generate-fn"
      (is (= :no-generate-fn (:reason (sut/fix-refusal {:worktree-path "/repo"} {:pr/branch "feat/x"})))))
    (testing "another branch checked out"
      (with-redefs [sut/checked-out-branch (constantly {:branch "main" :sha "c1"})]
        (is (= {:success? false :reason :worktree-not-on-pr-branch
                :pr/branch "feat/x" :pr/sha "c1" :checked-out {:branch "main" :sha "c1"}}
               (sut/fix-refusal (config calls) {:pr/branch "feat/x" :pr/sha "c1"})))))
    (testing "the PR's branch name at another commit (a fork's PR from a same-named branch)"
      (with-redefs [sut/checked-out-branch (constantly {:branch "main" :sha "upstream-tip"})]
        (is (= :worktree-not-on-pr-branch
               (:reason (sut/fix-refusal (config calls) {:pr/branch "main" :pr/sha "fork-tip"}))))))
    (testing "the PR's branch checked out"
      (with-redefs [sut/checked-out-branch (constantly {:branch "feat/x" :sha "c1"})]
        (is (nil? (sut/fix-refusal (config calls) {:pr/branch "feat/x" :pr/sha "c1"})))))))

(deftest ^{:stratum 1} change-request-on-wrong-branch-is-not-fixed-test
  (testing "the handler refuses before spending budget or calling generate-fn"
    (let [calls (atom 0)
          budget-changed (atom false)
          monitor (atom {:config (config calls)})]
      (with-redefs [sut/checked-out-branch (constantly {:branch "main" :sha "c1"})
                    state/get-or-create-budget (fn [_ pr-number] (budget/create-budget pr-number {}))
                    state/change-budget! (fn [& _] (reset! budget-changed true))]
        (is (= :worktree-not-on-pr-branch
               (:reason (handlers/handle-change-request
                         monitor {:pr/number 7 :pr/branch "feat/x" :pr/sha "c1"}
                         {:comment {:comment/id 1 :comment/body "Please rename foo"}})))))
      (is (zero? @calls))
      (is (false? @budget-changed)))))
