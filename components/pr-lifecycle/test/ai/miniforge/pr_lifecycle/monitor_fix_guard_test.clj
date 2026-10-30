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

(defn ^{:stratum 0} git!
  "Run git in `dir` with a throwaway identity and no signing; return stdout trimmed."
  [dir & args]
  (.trim ^String (:out (apply process/sh {:dir dir} "git"
                              "-c" "user.email=t@example.com" "-c" "user.name=t"
                              "-c" "commit.gpgsign=false" args))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} temp-repo
  "A git repository on branch feat/x with one commit; returns its path."
  []
  (let [dir (str (fs/create-temp-dir))]
    (git! dir "init" "-q" "-b" "feat/x")
    (git! dir "commit" "-q" "--allow-empty" "-m" "pr head")
    dir))

(deftest ^{:stratum 1} fix-refusal-test
  (let [calls (atom 0)]
    (testing "no generate-fn"
      (is (= :no-generate-fn (:reason (sut/fix-refusal {:worktree-path "/repo"} {:pr/branch "feat/x"})))))
    (testing "another branch checked out"
      (with-redefs [sut/checked-out-branch (constantly {:branch "main" :sha "c1"})
                    sut/head-builds-on? (constantly true)]
        (let [refusal (sut/fix-refusal (config calls) {:pr/branch "feat/x" :pr/sha "c1"})]
          (is (false? (:success? refusal)))
          (is (= :worktree-not-on-pr-branch (:reason refusal))))))
    (testing "the PR's branch name, but HEAD does not build on the PR's head (a fork's same-named branch)"
      (with-redefs [sut/checked-out-branch (constantly {:branch "main" :sha "upstream-tip"})
                    sut/head-builds-on? (constantly false)]
        (is (= :worktree-not-on-pr-branch
               (:reason (sut/fix-refusal (config calls) {:pr/branch "main" :pr/sha "fork-tip"}))))))
    (testing "the PR's branch at or after its head"
      (with-redefs [sut/checked-out-branch (constantly {:branch "feat/x" :sha "c2"})
                    sut/head-builds-on? (constantly true)]
        (is (nil? (sut/fix-refusal (config calls) {:pr/branch "feat/x" :pr/sha "c1"})))))
    (is (zero? @calls))))

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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} real-checkout-test
  (let [dir (temp-repo)
        pr-sha (git! dir "rev-parse" "HEAD")]
    (try
      (testing "branch and commit are read from the checkout"
        (is (= {:branch "feat/x" :sha pr-sha} (sut/checked-out-branch dir))))
      (testing "a second fix in the same cycle: HEAD has moved past the PR's head and still builds on it"
        (git! dir "commit" "-q" "--allow-empty" "-m" "first fix")
        (is (sut/head-builds-on? dir pr-sha))
        (is (nil? (sut/fix-refusal {:worktree-path dir :generate-fn (fn [& _])}
                                   {:pr/branch "feat/x" :pr/sha pr-sha}))))
      (testing "a commit HEAD does not contain, or one git does not have, is refused"
        (git! dir "checkout" "-q" "--orphan" "other")
        (git! dir "commit" "-q" "--allow-empty" "-m" "unrelated")
        (is (not (sut/head-builds-on? dir pr-sha)))
        (is (not (sut/head-builds-on? dir "0000000000000000000000000000000000000000")))
        (is (not (sut/head-builds-on? dir ""))))
      (finally (fs/delete-tree dir)))))
