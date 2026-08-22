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
(ns ai.miniforge.cli.worktree
  "Resolve the nearest checkout root, preferring the nearest .git marker.

   This makes nested linked worktrees safe: if an agent is launched inside a
   child worktree under a larger repository, we bind tooling to the child
  checkout instead of accidentally drifting up to the parent repo.

   The directory walk to the nearest `.git` marker lives in
   `ai.miniforge.cli.worktree.root-resolution` (rule 210: this namespace
   measured 5 layers, max 3); this file resolves the public
   `worktree-root` entry point against it and adds git metadata lookup."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [ai.miniforge.cli.worktree.root-resolution :as root-resolution]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} worktree-root
  "Return the canonical checkout root for start-path.

   Prefers the nearest .git marker. Falls back to git rev-parse only when
   directory walking does not find a marker."
  ([] (worktree-root (System/getProperty "user.dir")))
  ([start-path]
   (or (root-resolution/nearest-git-root start-path)
       (let [dir (or (root-resolution/canonical-dir start-path)
                     (System/getProperty "user.dir"))
             {:keys [exit out]} (shell/sh "git" "rev-parse" "--show-toplevel" :dir dir)]
         (when (zero? exit)
           (some-> out str/trim not-empty))))))

;------------------------------------------------------------------------------ Layer 1

;; Git metadata
(defn ^{:stratum 1} git-info
  "Return basic git metadata for the resolved worktree root.

   Returns nil when start-path is not inside a git checkout."
  ([] (git-info (System/getProperty "user.dir")))
  ([start-path]
   (when-let [root (worktree-root start-path)]
     (let [{branch-out :out} (shell/sh "git" "rev-parse" "--abbrev-ref" "HEAD" :dir root)
           {commit-out :out} (shell/sh "git" "rev-parse" "--short" "HEAD" :dir root)
           {git-dir-out :out} (shell/sh "git" "rev-parse" "--absolute-git-dir" :dir root)
           branch (some-> branch-out str/trim not-empty)
           commit (some-> commit-out str/trim not-empty)
           git-dir (some-> git-dir-out str/trim not-empty)]
       {:worktree-path root
        :git-branch branch
        :git-commit commit
        :git-dir git-dir}))))
