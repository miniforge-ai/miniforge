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
(ns ai.miniforge.gate.git-change-test
  "The shared git helpers read a zero-context diff into added lines and
   deletion points, and measure from the task base, never HEAD."
  (:require [ai.miniforge.gate.git-change :as git-change]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private diff-text
  "A zero-context diff with an addition, a deletion, and a change."
  "--- a/f\n+++ b/f\n@@ -2,0 +3,2 @@\n+x\n++y\n@@ -5 +8,0 @@\n-gone\n@@ -7 +9 @@\n-old\n+z\n")

(deftest ^{:stratum 0} scratch-dir-is-owner-only-test
  (let [dir (git-change/scratch-dir "mf-git-change-test-")]
    (try
      (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions dir))))
      (finally (fs/delete-tree dir)))))

(deftest ^{:stratum 0} base-candidates-never-fall-back-to-head-test
  (is (= ["refs/a" "refs/b" "abc123"]
         (git-change/base-candidates {:code/base-refs ["refs/a"]}
                                     {:execution/task-base-refs ["refs/b"]
                                      :execution/environment-metadata {:base-sha "abc123"}})))
  (is (empty? (git-change/base-candidates {} {}))))

(deftest ^{:stratum 0} version-diff-of-a-new-path-adds-every-line-test
  (let [dir (git-change/scratch-dir "mf-git-change-test-")]
    (try
      (is (= [[1 "a"] [2 "b"]]
             (git-change/added-lines (git-change/version-diff {} nil "new.clj" "a\nb\n" dir "0"))))
      (finally (fs/delete-tree dir)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} added-lines-follows-hunk-headers-test
  (is (= [[3 "x"] [4 "+y"] [9 "z"]] (git-change/added-lines diff-text))))

(deftest ^{:stratum 1} deletion-points-names-only-pure-deletions-test
  (is (= [8] (git-change/deletion-points diff-text)))
  (is (= [0] (git-change/deletion-points "@@ -1,2 +0,0 @@\n-a\n-b\n"))))
