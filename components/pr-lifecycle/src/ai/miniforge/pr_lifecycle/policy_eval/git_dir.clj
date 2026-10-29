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
(ns ai.miniforge.pr-lifecycle.policy-eval.git-dir
  "Recognise paths inside a worktree's git dir.

   A fix that writes `.git/hooks/pre-commit` or `.git/config` gets code
   run by the next git command, and the fix loop commits right after it
   writes. `policy-eval.fs/safe-worktree-path` and
   `safe-worktree-entry-path` therefore refuse any path this namespace
   places in the git dir.

   The git dir is `<root>/.git`: a directory in a clone, or a
   `gitdir: <path>` file in a linked worktree or submodule, in which case
   the directory that file names is a git dir too. Any path segment named
   `.git` in any case is refused as well, since git itself will not track
   such a path and a case-insensitive file system maps `.GIT` onto `.git`."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git-dir-name?
  "True when path segment `segment` is `.git` in any letter case."
  [segment]
  (= ".git" (str/lower-case (str segment))))

(defn ^{:stratum 0} gitdir-file-target
  "Canonical path of the directory a `gitdir: <path>` file names, or nil
   when `dot-git` is not such a file. A relative path is resolved against
   the file's own directory, as git does."
  [^java.io.File dot-git]
  (when (.isFile dot-git)
    (let [line   (str/trim (or (first (str/split-lines (slurp dot-git))) ""))
          prefix "gitdir:"]
      (when (str/starts-with? line prefix)
        (let [target (io/file (str/trim (subs line (count prefix))))]
          (.getCanonicalPath (if (.isAbsolute target)
                               target
                               (io/file (.getParentFile dot-git) (str target)))))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-dirs
  "Canonical paths of the git dirs of the worktree at canonical `root`:
   `<root>/.git` when it exists, plus the target of a gitdir file."
  [root]
  (let [dot-git (io/file root ".git")
        target  (gitdir-file-target dot-git)]
    (cond-> []
      (.exists dot-git) (conj (.getCanonicalPath dot-git))
      target            (conj target))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} inside-git-dir?
  "True when `path`, an absolute path at or under `worktree-root` whose
   parent is already canonical, is a git dir or inside one: a segment
   below the root is named `.git` (any case), or the path equals or sits
   under one of `git-dirs`."
  [worktree-root path]
  (let [root     (.getCanonicalPath (io/file (str worktree-root)))
        path     (str path)
        relative (if (str/starts-with? path root) (subs path (count root)) path)]
    (boolean
     (or (some git-dir-name? (str/split relative #"[/\\\\]"))
         (some (fn [dir]
                 (or (= path dir)
                     (str/starts-with? path (str dir java.io.File/separator))))
               (git-dirs root))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (git-dir-name? ".GIT")                                    ; => true
  (inside-git-dir? "/repo" "/repo/.git/hooks/pre-commit")   ; => true
  (inside-git-dir? "/repo" "/repo/src/core.clj")            ; => false

  :leave-this-here)
