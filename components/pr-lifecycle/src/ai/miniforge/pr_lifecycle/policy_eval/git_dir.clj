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
  "Recognise paths that reach a worktree's git metadata.

   A fix that writes `.git/hooks/pre-commit`, `.git/config`, or a file in
   the directory `core.hooksPath` names gets code run by the next git
   command, and the fix loop commits right after it writes.
   `policy-eval.fs/safe-worktree-path` and `safe-worktree-entry-path`
   refuse any path this namespace places in that metadata.

   Git has several ways to locate its metadata (a `.git` directory, a
   `gitdir:` file, a linked worktree's `commondir`, `core.hooksPath`), so
   this namespace does not re-implement them. It asks git, run in the
   worktree, for its git dir, common dir and hooks dir, and checks a path
   against those. When git cannot answer, every path is refused. A path
   segment named `.git` in any case is refused without asking: git itself
   will not track such a path, and a case-insensitive file system maps
   `.GIT` onto `.git`."
  (:require
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git-env
  "This process's environment without the variables that make git use
   another repository than the one its working directory is in. A git
   hook exports these to its children; dropping them makes a query answer
   for the worktree. Same list the test launcher strips
   (`bb-test-runner/sanitize-git-worktree-env`)."
  []
  (apply dissoc (into {} (System/getenv))
         ["GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_COMMON_DIR"]))

(defn ^{:stratum 0} git-dir-name?
  "True when path segment `segment` is `.git` in any letter case."
  [segment]
  (= ".git" (str/lower-case (str segment))))

(defn ^{:stratum 0} segments
  "The non-empty `/`- or `\\`-separated components of `path`."
  [path]
  (remove str/blank? (str/split (str path) #"[/\\\\]")))

(defn ^{:stratum 0} under?
  "True when canonical `path` is `dir` or sits below it."
  [dir path]
  (or (= path dir)
      (str/starts-with? path (str dir java.io.File/separator))))

(defn ^{:stratum 0} canonical-under
  "Canonical path of `path`, resolved against `root` when relative."
  [root path]
  (let [file (io/file path)]
    (.getCanonicalPath (if (.isAbsolute file) file (io/file root path)))))

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

(defn ^{:stratum 1} metadata-dirs
  "Ask git, run in canonical `root`, where this worktree's metadata lives:
   `--absolute-git-dir`, `--git-common-dir` (shared by linked worktrees),
   and `--git-path hooks` (which honours `core.hooksPath`). Returns their
   canonical paths, or nil when git cannot answer: not a repository, git
   missing, or any other failure."
  [root]
  (try
    (let [{:keys [exit out]} (process/sh {:dir root :env (git-env) :out :string :err :string}
                                         "git" "rev-parse" "--absolute-git-dir"
                                         "--git-common-dir" "--git-path" "hooks")
          ;; One path per line, spaces kept: git treats them as part of the name.
          dirs (str/split-lines (str out))]
      (when (and (zero? exit) (= 3 (count dirs)))
        (mapv #(canonical-under root %) dirs)))
    (catch Exception _ nil)))

(defn ^{:stratum 1} in-metadata?
  "True when canonical `path` is metadata of the worktree at canonical
   `root`: a component below `root` is named `.git`, or `path` is at or
   under one of `dirs`."
  [root dirs path]
  (let [relative (if (under? root path) (subs path (count root)) path)]
    (boolean (or (some git-dir-name? (segments relative))
                 (some #(under? % path) dirs)))))

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

(defn ^{:stratum 2} reaches-metadata?
  "True when following `relative-path` from `worktree-root` reaches git
   metadata: the canonical target, or the canonical directory at any step
   on the way to it, is in `in-metadata?`. Checking every step catches a
   symlink into the git dir followed by one back out. Also true when git
   cannot say where the metadata is."
  [worktree-root relative-path]
  (let [root  (.getCanonicalPath (io/file (str worktree-root)))
        dirs  (metadata-dirs root)
        parts (vec (segments relative-path))]
    (boolean
     (or (nil? dirs)
         (some (fn [n]
                 (in-metadata? root dirs (.getCanonicalPath ^java.io.File
                                                            (apply io/file root (subvec parts 0 n)))))
               (range 1 (inc (count parts))))))))

(defn ^{:stratum 2} metadata-entry?
  "True when the directory entry `entry` (an absolute path whose parent is
   canonical; the entry itself is not followed) is git metadata of the
   worktree at `worktree-root`, or git cannot say where that is. For
   deletes, which remove the entry and not what it points at."
  [worktree-root entry]
  (let [root (.getCanonicalPath (io/file (str worktree-root)))
        dirs (metadata-dirs root)]
    (or (nil? dirs) (in-metadata? root dirs (str entry)))))

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
  (git-dir-name? ".GIT")                                  ; => true
  (reaches-metadata? "/repo" ".githooks/pre-commit")      ; => true when core.hooksPath = .githooks
  (reaches-metadata? "/repo" "src/core.clj")              ; => false in a repository

  :leave-this-here)
