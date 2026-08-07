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
(ns bench-test
  "Pins the isolation contract of the bench/dogfood sandbox.

   The 2026-08-06 incident: the bench sandbox was a linked `git worktree`
   of the live checkout, so redirecting the bench's origin at a local bare
   mirror redirected the live checkout's origin too, and the bench's
   `git fetch origin main` force-updated the live checkout's `origin/main`
   backwards to the mirror's stale head. These tests run real git against
   throwaway repos — the leak is a property of how git shares a common
   dir, so a mocked git would not observe it."
  (:require
   [bench :as sut]
   [bench-git :as git]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} launching-origin
  "Obviously synthetic — `.invalid` is reserved and can never resolve, so a
   test that accidentally reaches the network fails loudly instead of
   touching a real remote."
  "https://example.invalid/launching-origin.git")

(defn- ^{:stratum 0} temp-dir []
  (str (Files/createTempDirectory "miniforge-bench-test" (make-array FileAttribute 0))))

(defn- ^{:stratum 0} delete-tree! [path]
  (let [f (File. (str path))]
    (when (.exists f)
      (when (.isDirectory f)
        (doseq [child (.listFiles f)] (delete-tree! child)))
      (.delete f))))

(defn- ^{:stratum 0} heads [dir]
  (->> (:out (git/git dir "for-each-ref" "--format=%(refname)" "refs/heads"))
       str str/split-lines (remove str/blank?) set))

(deftest ^{:stratum 0} qualified-branch-ref-prefixes-exactly-once-test
  (testing "every accepted spelling normalizes to one refs/heads/ prefix"
    (is (= "refs/heads/main" (sut/qualified-branch-ref "main")))
    (is (= "refs/heads/main" (sut/qualified-branch-ref "refs/heads/main")))
    (is (= "refs/heads/main" (sut/qualified-branch-ref "heads/main")))
    (is (= "refs/heads/main" (sut/qualified-branch-ref "refs/heads/heads/main"))
        "the doubled prefix found in the bench mirror collapses to one"))
  (testing "an interior heads/ segment is part of the branch name, not a prefix"
    (is (= "refs/heads/feature/heads-up" (sut/qualified-branch-ref "feature/heads-up")))
    (is (= "refs/heads/fix/heads/nested" (sut/qualified-branch-ref "refs/heads/fix/heads/nested"))))
  (testing "a name that is nothing but prefixes has no ref, rather than an empty one"
    (is (nil? (sut/qualified-branch-ref "refs/heads/")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} init-launching-repo!
  "A stand-in for the checkout an operator launches the bench from: one
   commit, and an `origin` that must survive the run untouched."
  [dir]
  (git/git dir "init" "--quiet" "-b" "main")
  (git/git dir "config" "user.email" "bench-test@example.invalid")
  (git/git dir "config" "user.name" "Bench Test")
  (git/git dir "config" "commit.gpgsign" "false")
  (spit (str (File. (str dir) "seed.txt")) "seed\n")
  (git/git dir "add" "seed.txt")
  (git/git dir "commit" "--quiet" "-m" "seed")
  (git/git dir "remote" "add" "origin" launching-origin)
  dir)

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} provision-leaves-launching-origin-unchanged-test
  (testing "a provisioned bench never rewrites the launching checkout's origin"
    (let [root (temp-dir)
          source (init-launching-repo! (temp-dir))]
      (try
        (let [report (sut/provision! {:source source :root (str (File. root "bench"))})]
          (is (= launching-origin (git/remote-url source "origin"))
              "the regression: the launching checkout's origin is untouched")
          (is (= launching-origin (:source-origin report)))
          (is (= (:mirror-dir report) (git/remote-url (:repo-dir report) "origin"))
              "only the bench points at the throwaway mirror")
          (is (:isolated? report))
          (is (false? (:linked-worktree? report)))
          (is (false? (:shares-source-git-dir? report))))
        (finally
          (delete-tree! root)
          (delete-tree! source))))))

(deftest ^{:stratum 2} provision-seeds-mirror-without-doubled-prefix-test
  (testing "the mirror carries exactly the qualified branch it was told to"
    (let [root (temp-dir)
          source (init-launching-repo! (temp-dir))]
      (try
        (let [report (sut/provision! {:source source :root (str (File. root "bench"))})]
          (is (= #{"refs/heads/main"} (heads (:mirror-dir report)))))
        (finally
          (delete-tree! root)
          (delete-tree! source))))))

(deftest ^{:stratum 2} provision-refuses-to-clobber-an-existing-bench-test
  (testing "an in-flight bench keeps its worktrees and task branches"
    (let [root (temp-dir)
          source (init-launching-repo! (temp-dir))
          bench-root (str (File. root "bench"))]
      (try
        (sut/provision! {:source source :root bench-root})
        (is (= :conflict (:anomaly/type (sut/provision! {:source source :root bench-root}))))
        (finally
          (delete-tree! root)
          (delete-tree! source))))))

(deftest ^{:stratum 2} linked-worktree-is-reported-unisolated-test
  (testing "the shape the 2026-08-06 bench actually had is rejected"
    (let [source (init-launching-repo! (temp-dir))
          parent (temp-dir)
          leaky (str (File. parent "leaky-bench"))]
      (try
        (git/git source "worktree" "add" "--quiet" "--detach" leaky)
        (let [report (sut/isolation-report leaky source)]
          (is (false? (:isolated? report)))
          (is (true? (:linked-worktree? report)))
          (is (true? (:shares-source-git-dir? report))))
        (testing "and the leak it guards against is real: set-url inside the
                  worktree rewrites the launching checkout's origin"
          (git/git leaky "remote" "set-url" "origin" "https://example.invalid/bench-mirror.git")
          (is (not= launching-origin (git/remote-url source "origin"))))
        (finally
          (git/git source "worktree" "remove" "--force" leaky)
          (delete-tree! parent)
          (delete-tree! source))))))
