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

(ns ai.miniforge.agent.curator-merge-resolution-test
  "Tests for the curator's `:merge-resolution` method (v2 Stage 2).

   The resolution sub-workflow's iteration loop calls `(curate (assoc
   input :curator/kind :merge-resolution))` between iterations to
   decide whether the agent's edits cleared the conflict markers and
   whether progress is being made. These tests pin the three
   outcomes — clean, markers-not-resolved, recurring-conflict —
   against synthetic worktree state."
  (:require
   [ai.miniforge.agent.curator :as sut]
   [ai.miniforge.response.interface :as response]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Fixture: temp worktree

(def ^:dynamic *worktree* nil)

(defn temp-worktree-fixture [f]
  (let [w (str (fs/create-temp-dir {:prefix "curator-resolution-test-"}))]
    (try
      (binding [*worktree* w] (f))
      (finally (try (fs/delete-tree w) (catch Throwable _ nil))))))

(use-fixtures :each temp-worktree-fixture)

;------------------------------------------------------------------------------ Helpers

(defn- write-file! [rel content]
  (let [f (java.io.File. ^String *worktree* ^String rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(def conflict-marker-content
  "Synthetic file content with git's conflict markers. Uses the
   default 7-character marker length git emits."
  (str "before\n"
       "<<<<<<< HEAD\n"
       "ours\n"
       "=======\n"
       "theirs\n"
       ">>>>>>> branch-b\n"
       "after\n"))

(def resolved-content
  "Same logical file with markers gone (the agent picked a side)."
  "before\nresolved\nafter\n")

;------------------------------------------------------------------------------ Tests

(deftest merge-resolution-success-when-no-markers-test
  (testing "When the worktree has no files with conflict markers, the
            curator returns response/success. The verify gate above
            then runs project tests; the curator's role is just the
            marker check."
    (write-file! "src/a.clj" "(ns a)\n;; clean code\n")
    (write-file! "src/b.clj" resolved-content)
    (let [result (sut/curate {:curator/kind :merge-resolution
                              :worktree-path *worktree*})]
      (is (response/success? result))
      (is (true? (get-in result [:output :resolution/markers-cleared?]))
          "the success envelope flags that markers were cleared so the
           caller can distinguish 'no work needed' from 'work happened'"))))

(deftest merge-resolution-markers-not-resolved-test
  (testing "Conflict markers in the tree on first iteration produce a
            terminal :curator/markers-not-resolved error with the
            offending paths in :data."
    (write-file! "src/conflict.clj" conflict-marker-content)
    (write-file! "src/clean.clj" "(ns clean)\n")
    (let [result (sut/curate {:curator/kind :merge-resolution
                              :worktree-path *worktree*})]
      (is (response/error? result))
      (is (= :curator/markers-not-resolved
             (get-in result [:error :data :code])))
      (is (= ["src/conflict.clj"]
             (get-in result [:error :data :conflicted-paths]))
          "only the file that actually had markers is listed"))))

(deftest merge-resolution-recurring-conflict-when-paths-unchanged-test
  (testing "When the conflicted-path set this iteration matches the
            prior iteration's, the curator returns the
            :curator/recurring-conflict terminal — the loop should
            terminate before exhausting the rest of the budget. This
            is the 'agent is stuck' early-out per spec §6.1.2."
    (write-file! "src/x.clj" conflict-marker-content)
    (write-file! "src/y.clj" conflict-marker-content)
    (let [result (sut/curate {:curator/kind :merge-resolution
                              :worktree-path *worktree*
                              :prior-conflicted-paths #{"src/x.clj" "src/y.clj"}})]
      (is (response/error? result))
      (is (= :curator/recurring-conflict
             (get-in result [:error :data :code]))
          "same path set ⇒ recurring, not just markers-not-resolved"))))

(deftest merge-resolution-progress-yields-markers-not-resolved-test
  (testing "When markers remain but the path set has shifted (some
            resolved, others surfaced), it's NOT recurring — the
            terminal is :curator/markers-not-resolved so the loop
            re-prompts. The recurring-conflict terminal only fires
            when the agent makes literally zero progress."
    (write-file! "src/x.clj" conflict-marker-content)
    (write-file! "src/z.clj" conflict-marker-content)
    (let [result (sut/curate {:curator/kind :merge-resolution
                              :worktree-path *worktree*
                              :prior-conflicted-paths #{"src/x.clj" "src/y.clj"}})]
      (is (response/error? result))
      (is (= :curator/markers-not-resolved
             (get-in result [:error :data :code]))
          "different path set ⇒ make-progress, not recurring"))))

(deftest merge-resolution-no-prior-paths-acts-like-first-iteration-test
  (testing "When `prior-conflicted-paths` is nil/missing, recurrence
            can't be detected — only :curator/markers-not-resolved
            fires. This is the first-iteration call shape; the loop
            above sets prior-conflicted-paths from this iteration's
            result for the next call."
    (write-file! "src/conflict.clj" conflict-marker-content)
    (let [result (sut/curate {:curator/kind :merge-resolution
                              :worktree-path *worktree*})]
      (is (response/error? result))
      (is (= :curator/markers-not-resolved
             (get-in result [:error :data :code]))))))

(deftest merge-resolution-default-dispatch-still-implements-test
  (testing "Backward compatibility: an input WITHOUT `:curator/kind`
            still dispatches to `:implement` (so the legacy
            `curate-implement-output` wrapper and any direct callers
            of `curate` that haven't been updated keep working)."
    (let [result (sut/curate {:implementer-result {:output {:code/files []}}
                              :worktree-path *worktree*})]
      (is (response/error? result))
      (is (= :curator/no-files-written
             (get-in result [:error :data :code]))
          "default dispatch hits :implement and produces the implement-flow's
           empty-diff terminal — proving the multimethod refactor didn't
           change the legacy contract"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.curator-merge-resolution-test)

  :leave-this-here)
