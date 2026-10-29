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
  "Recognise paths a fix must not write: ones that pass through a symbolic
   link, and ones that reach a worktree's git metadata.

   A fix that writes `.git/hooks/pre-commit`, `.git/config`, or a file in
   the directory `core.hooksPath` names gets code run by a later git
   command. `policy-eval.fs/safe-worktree-path` and
   `safe-worktree-entry-path` refuse any path this namespace flags.

   Symlinks are refused outright: a path any existing component of which,
   the last included, is a symbolic link (dangling or not). An
   LLM-proposed fix has no need to write through a link, and every
   symlink alias into the metadata is closed at once.

   Git has several ways to locate its metadata (a `.git` directory, a
   `gitdir:` file, a linked worktree's `commondir`, `core.hooksPath`), so
   this namespace does not re-implement them. It asks git, run in the
   worktree, for its git dir, common dir and hooks dir. A symlink directly
   inside one of those (a symlinked `.git/config` or `.git/refs`, a hook
   linked to a script) also protects where it finally points, dangling or
   chained, and everything below that, except a path git's index holds
   exactly: a tracked file shows in the PR diff and stays editable, and
   the fix loop runs git with hooks disabled (`hooks-disabled-args`). When
   git cannot answer, every path is refused.

   Paths are compared as java.nio components, never by splitting strings:
   a whitespace-only name is a name, and on POSIX a backslash is part of
   one. A component named `.git` in any case is refused before any of
   this: git will not track such a path, and a case-insensitive file
   system maps `.GIT` onto `.git`."
  (:require
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} hooks-disabled-args
  "Global git options that stop every hook from running, for the git
   commands the fix loop issues after writing LLM-proposed files. No hook
   can exist below /dev/null."
  ["-c" "core.hooksPath=/dev/null"])

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
  "True when path component `segment` is `.git` in any letter case."
  [segment]
  (= ".git" (str/lower-case (str segment))))

(defn ^{:stratum 0} segments
  "The names of `path`'s components as the file system splits them."
  [path]
  (let [p (java.nio.file.Paths/get (str path) (make-array String 0))]
    (mapv #(str (.getName p %)) (range (.getNameCount p)))))

(defn ^{:stratum 0} symlink-on-path?
  "True when any existing component of `relative-path` under
   `worktree-root`, the last included, is a symbolic link, dangling or
   not. Each prefix is checked with `Files/isSymbolicLink`, which reads
   the entry's own attributes (NOFOLLOW_LINKS). A path java.nio cannot
   represent counts as one."
  [worktree-root relative-path]
  (try+
   (let [root (java.nio.file.Paths/get (str worktree-root) (make-array String 0))
         rel  (java.nio.file.Paths/get (str relative-path) (make-array String 0))]
     (boolean (some #(java.nio.file.Files/isSymbolicLink (.resolve root (.subpath rel 0 %)))
                    (range 1 (inc (.getNameCount rel))))))
   (catch IllegalArgumentException _ true)))

(defn ^{:stratum 0} under?
  "True when absolute `path` is `dir` or below it, component by component."
  [dir path]
  (.startsWith (java.nio.file.Paths/get (str path) (make-array String 0))
               (java.nio.file.Paths/get (str dir) (make-array String 0))))

(defn ^{:stratum 0} canonical-under
  "Canonical path of `path`, resolved against `root` when relative."
  [root path]
  (let [file (io/file path)]
    (.getCanonicalPath (if (.isAbsolute file) file (io/file root path)))))

(defn ^{:stratum 0} link-targets
  "Where each symbolic link directly inside directory `dir` finally
   points, as canonical paths. Follows a chain of links, resolving each
   target against its link's directory, so a dangling link or chain still
   yields the path it would create; `getCanonicalPath` alone stops at a
   dangling link itself."
  [dir]
  (for [^java.io.File f (.listFiles (io/file dir))
        :when (java.nio.file.Files/isSymbolicLink (.toPath f))]
    (loop [^java.nio.file.Path p (.toPath f)
           hops 0]
      (if (and (java.nio.file.Files/isSymbolicLink p) (< hops 40))
        (recur (.resolve (.getParent p) (java.nio.file.Files/readSymbolicLink p)) (inc hops))
        (.getCanonicalPath (.toFile p))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} metadata
  "Ask git, run in canonical `root`, where this worktree's metadata lives.
   Returns `{:dirs [...] :links [...]}` of canonical paths, or nil when git
   cannot answer (not a repository, git missing, any other failure).
   `:dirs` are `--absolute-git-dir`, `--git-common-dir` and
   `--git-path hooks` (which honours `core.hooksPath`). `:links` are the
   final targets of symlinks directly inside those that point elsewhere
   in the worktree."
  [root]
  (try+
   ;; One path per line, spaces kept: git treats them as part of the name.
   (let [rev  (process/sh {:dir root :env (git-env)}
                          "git" "rev-parse" "--absolute-git-dir"
                          "--git-common-dir" "--git-path" "hooks")
         dirs (when (zero? (:exit rev))
                (mapv #(canonical-under root %) (str/split-lines (str (:out rev)))))]
     (when (= 3 (count dirs))
       {:dirs  dirs
        :links (->> (distinct dirs)
                    (mapcat link-targets)
                    (remove (fn [target] (some #(under? % target) dirs)))
                    (filter #(under? root %))
                    distinct
                    vec)}))
   (catch Exception _ nil)))

(defn ^{:stratum 1} tracked-exactly?
  "True when git's index holds exactly canonical `path` of the worktree at
   canonical `root`; a tracked path below it does not count. False when
   git cannot answer."
  [root path]
  (try+
   (let [rel (.relativize (java.nio.file.Paths/get (str root) (make-array String 0))
                          (java.nio.file.Paths/get (str path) (make-array String 0)))
         {:keys [exit out]} (process/sh {:dir root :env (git-env)}
                                        "git" "--literal-pathspecs" "ls-files" "-z" "--"
                                        (str rel))]
     (boolean (and (zero? exit)
                   (some #(= rel (java.nio.file.Paths/get (str %) (make-array String 0)))
                         (remove str/blank? (str/split (str out) #"\u0000"))))))
   (catch Exception _ false)))

(defn ^{:stratum 1} in-metadata?
  "True when canonical `path` is at or under one of `found`'s `:dirs`, or
   at or under one of its `:links` and `(tracked? path)` is false."
  [{:keys [dirs links]} path tracked?]
  (boolean (or (some #(under? % path) dirs)
               (and (some #(under? % path) links)
                    (not (tracked? path))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reaches-metadata?
  "True when `relative-path` under `worktree-root` names git metadata: its
   canonical target is `in-metadata?`, or git cannot say where the
   metadata is. Callers refuse paths with a symlink component first
   (`symlink-on-path?`), so the canonical target is the path itself."
  [worktree-root relative-path]
  (let [root  (.getCanonicalPath (io/file (str worktree-root)))
        found (metadata root)]
    (boolean
     (or (nil? found)
         (in-metadata? found (.getCanonicalPath (io/file root (str relative-path)))
                       #(tracked-exactly? root %))))))

(defn ^{:stratum 2} metadata-entry?
  "True when the directory entry `entry` (an absolute path whose parent is
   canonical) is git metadata of the worktree at `worktree-root`, or git
   cannot say where that is. For deletes. A name the file system cannot
   represent counts as metadata."
  [worktree-root entry]
  (try+
   (let [root  (.getCanonicalPath (io/file (str worktree-root)))
         found (metadata root)]
     (or (nil? found)
         (in-metadata? found (str entry) #(tracked-exactly? root %))))
   (catch IllegalArgumentException _ true)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (git-dir-name? ".GIT")                                  ; => true
  (symlink-on-path? "/repo" "tools/hooks/pre-commit")     ; => true when tools is a link
  (reaches-metadata? "/repo" ".githooks/pre-commit")      ; => true when core.hooksPath = .githooks
  (reaches-metadata? "/repo" "src/core.clj")              ; => false in a repository

  :leave-this-here)
