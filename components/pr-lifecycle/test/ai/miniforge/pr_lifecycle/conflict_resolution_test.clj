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

(ns ai.miniforge.pr-lifecycle.conflict-resolution-test
  "Stage 3 slices 3a + 3b: tests for the pure-data classifiers, the
   conflict-input builder, and the git-plumbing probes
   (extract-conflict-paths, push-resolution!). Slice 3c adds tests
   for the orchestrator entry point."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.conflict-resolution :as sut]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Test data

(def ^:private sample-pr
  {:pr/id        123
   :pr/branch    "feat/widget"
   :pr/base      "main"
   :pr/head-sha  "abc1234deadbeef"
   :pr/base-sha  "def5678cafebabe"})

;------------------------------------------------------------------------------ classify-merge-state

(deftest classify-merge-state-recognizes-conflicting-test
  (testing "GitHub mergeable=CONFLICTING and/or mergeStateStatus=DIRTY
            classify as :conflicting — the only signals the §6.4
            hook should auto-engage on."
    (is (= :conflicting
           (sut/classify-merge-state
            "{\"mergeable\":\"CONFLICTING\",\"mergeStateStatus\":\"DIRTY\"}")))
    (is (= :conflicting
           (sut/classify-merge-state
            "{\"mergeable\":\"UNKNOWN\",\"mergeStateStatus\":\"DIRTY\"}"))
        "DIRTY alone is enough — GitHub flags conflicts that way")))

(deftest classify-merge-state-recognizes-mergeable-test
  (testing "Clean mergeable PRs classify as :mergeable so the
            caller's auto-engage check short-circuits."
    (is (= :mergeable
           (sut/classify-merge-state
            "{\"mergeable\":\"MERGEABLE\",\"mergeStateStatus\":\"CLEAN\"}")))))

(deftest classify-merge-state-defaults-to-unknown-test
  (testing "Anything we can't confidently classify falls into
            :unknown. Nil / blank / weird payloads must not auto-
            trigger resolution — the caller polls or surfaces
            instead."
    (is (= :unknown (sut/classify-merge-state nil)))
    (is (= :unknown (sut/classify-merge-state "")))
    (is (= :unknown (sut/classify-merge-state "  ")))
    (is (= :unknown
           (sut/classify-merge-state
            "{\"mergeable\":\"UNKNOWN\",\"mergeStateStatus\":\"PENDING\"}"))
        "GitHub still computing → :unknown, not :mergeable")))

;------------------------------------------------------------------------------ build-pr-conflict-input

(deftest build-pr-conflict-input-shape-test
  (testing "Two-parent shape per spec §6.4: PR branch first (order 0),
            base second (order 1). Order is fixed so replays produce
            the same input-key. Conflicts carry the placeholder
            stages vector that build-resolution-prompt joins with '/'
            for display."
    (let [paths ["src/conflict.txt" "src/other.clj"]
          input (sut/build-pr-conflict-input sample-pr paths)
          parents (:merge/parents input)]
      (is (= "pr-123" (:task/id input)))
      (is (= 2 (count parents))
          "always two-parent for the §6.4 surface")
      (is (= [:pr-branch :pr-base] (mapv :task/id parents)))
      (is (= [0 1] (mapv :order parents))
          "PR branch is order 0; base is order 1")
      (is (= ["feat/widget" "main"] (mapv :branch parents)))
      (is (= ["abc1234deadbeef" "def5678cafebabe"]
             (mapv :commit-sha parents)))
      (is (= :git-merge (:merge/strategy input)))
      (is (str/starts-with? (:merge/input-key input) "pr-123-")
          "input-key carries the head+base pair so different syncs
           produce different keys — no idempotency cache collisions
           across distinct main-tip moves")
      (is (= 2 (count (:merge/conflicts input))))
      (is (= ["src/conflict.txt" "src/other.clj"]
             (mapv :path (:merge/conflicts input)))))))

(deftest build-pr-conflict-input-empty-paths-test
  (testing "Zero conflict paths still produces a valid shape with
            an empty :merge/conflicts vector. Caller's no-conflicts
            check is what prevents the dead-end resolution
            invocation; the builder itself is total."
    (let [input (sut/build-pr-conflict-input sample-pr [])]
      (is (= [] (:merge/conflicts input))))))

;------------------------------------------------------------------------------ extract-conflict-paths (real git fixture)

(defn- run-git! [cwd & args]
  (let [r (apply shell/sh "git" "-C" cwd args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed: "
                           (:err r))
                      {:cwd cwd :result r})))
    r))

(defn- temp-conflict-repo!
  "Set up a real repo with an induced conflict between two branches.
   Returns the repo path; caller deletes when done."
  []
  (let [repo (str (fs/create-temp-dir {:prefix "pr-conflict-test-"}))]
    (run-git! repo "init" "-b" "main")
    (run-git! repo "config" "user.email" "test@miniforge.ai")
    (run-git! repo "config" "user.name" "miniforge-test")
    (run-git! repo "config" "commit.gpgsign" "false")
    (run-git! repo "config" "tag.gpgsign" "false")
    (spit (str repo "/README.md") "init\n")
    (run-git! repo "add" "README.md")
    (run-git! repo "commit" "-m" "init")
    (run-git! repo "checkout" "-b" "branch-a" "main")
    (spit (str repo "/conflict.txt") "from a\n")
    (run-git! repo "add" "conflict.txt")
    (run-git! repo "commit" "-m" "a")
    (run-git! repo "checkout" "main")
    (spit (str repo "/conflict.txt") "from main\n")
    (run-git! repo "add" "conflict.txt")
    (run-git! repo "commit" "-m" "main")
    ;; merge attempt produces the conflict markers + unmerged index
    (let [m (shell/sh "git" "-C" repo "merge" "--no-edit"
                      "--no-gpg-sign" "--no-verify" "branch-a")]
      (assert (not (zero? (:exit m)))
              "fixture expected merge to conflict"))
    repo))

(deftest extract-conflict-paths-returns-unmerged-paths-test
  (testing "extract-conflict-paths reads `git diff --name-only
            --diff-filter=U` so an unmerged index surfaces as a
            vector of relative paths. Real-git fixture rather than
            a mock — the filter behaviour is git's, not ours, so a
            mock would just re-encode our own assumption."
    (let [repo (temp-conflict-repo!)]
      (try
        (let [r (sut/extract-conflict-paths repo)]
          (is (dag/ok? r))
          (is (= ["conflict.txt"] (:data r))))
        (finally (fs/delete-tree repo))))))

(deftest extract-conflict-paths-empty-when-no-conflicts-test
  (testing "Clean worktree → empty vector, not nil. Caller branches
            on (empty? paths) and would mis-handle nil."
    (let [repo (str (fs/create-temp-dir {:prefix "pr-clean-test-"}))]
      (try
        (run-git! repo "init" "-b" "main")
        (run-git! repo "config" "user.email" "t@t")
        (run-git! repo "config" "user.name" "t")
        (run-git! repo "config" "commit.gpgsign" "false")
        (spit (str repo "/x.txt") "x\n")
        (run-git! repo "add" "x.txt")
        (run-git! repo "commit" "-m" "x")
        (let [r (sut/extract-conflict-paths repo)]
          (is (dag/ok? r))
          (is (= [] (:data r))))
        (finally (fs/delete-tree repo))))))

;------------------------------------------------------------------------------ rev-parse + push-resolution! (push mocked — no remote)

(deftest rev-parse-resolves-head-test
  (testing "rev-parse against HEAD on a fresh init+commit returns
            the 40-char commit SHA via dag/ok."
    (let [repo (str (fs/create-temp-dir {:prefix "pr-rev-test-"}))]
      (try
        (run-git! repo "init" "-b" "main")
        (run-git! repo "config" "user.email" "t@t")
        (run-git! repo "config" "user.name" "t")
        (run-git! repo "config" "commit.gpgsign" "false")
        (spit (str repo "/x.txt") "x\n")
        (run-git! repo "add" "x.txt")
        (run-git! repo "commit" "-m" "x")
        (let [r (sut/rev-parse repo "HEAD")]
          (is (dag/ok? r))
          (is (= 40 (count (:sha (:data r))))
              "rev-parse returns full SHA"))
        (finally (fs/delete-tree repo))))))

(deftest push-resolution-surfaces-failure-when-no-remote-test
  (testing "push-resolution! against a repo with no `origin` remote
            surfaces git's failure as a dag/err with :command-failed.
            Without this, an unconfigured remote would silently
            succeed-look (no exception thrown) and the orchestrator
            would think the resolution landed."
    (let [repo (str (fs/create-temp-dir {:prefix "pr-push-test-"}))]
      (try
        (run-git! repo "init" "-b" "main")
        (run-git! repo "config" "user.email" "t@t")
        (run-git! repo "config" "user.name" "t")
        (run-git! repo "config" "commit.gpgsign" "false")
        (spit (str repo "/x.txt") "x\n")
        (run-git! repo "add" "x.txt")
        (run-git! repo "commit" "-m" "x")
        (let [r (sut/push-resolution! repo "feat/x")]
          (is (dag/err? r))
          (is (= :command-failed (:code (:error r)))))
        (finally (fs/delete-tree repo))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.pr-lifecycle.conflict-resolution-test)

  :leave-this-here)
