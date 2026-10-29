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
(ns ai.miniforge.pr-lifecycle.fix-stage
  "Stage and commit a fix's files under git's own index lock, so a
   failure leaves the worktree's index as it was and no concurrent git
   command can be overwritten.

   This follows git's protocol for writing an index: create
   `<gitdir>/index.lock` exclusively (another git process holding it
   makes the fix fail), copy the index into it and replace that with HEAD
   (`git read-tree HEAD`, which needs a valid index to read), stage
   exactly the fix's files and commit with GIT_INDEX_FILE pointing at the
   lock file, then rename the lock file over the index on success or
   delete it on any failure. Other git commands see the lock and wait or
   fail, as they do for git itself. A retry therefore never commits what
   an earlier attempt staged.

   The commit holds HEAD plus the fix's files and nothing else. When the
   index already differs from HEAD (someone staged changes in the
   worktree), the fix is refused rather than committing or discarding
   them.

   GIT_INDEX_FILE is never inherited: the preflight sets it to
   `<gitdir>/index`, the index this replaces; staging and the commit set
   it to the lock file; every other call here clears it.

   The git dir comes from `git rev-parse --absolute-git-dir`, so a relative
   worktree path and a linked worktree both resolve to the right index.
   Every git command here runs with hooks disabled
   (`git-dir/hooks-disabled-args`; `git add` can run post-index-change)
   and literal pathspecs, so a name with glob characters stages only
   itself."
  (:require
   [ai.miniforge.pr-lifecycle.policy-eval.git-dir :as git-dir]
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} git-env
  "`environment` (a map) without GIT_INDEX_FILE, or with it set to `index`
   when given, for one git call."
  [environment index]
  (cond-> (dissoc (into {} environment) "GIT_INDEX_FILE")
    index (assoc "GIT_INDEX_FILE" (str index))))

(defn ^{:stratum 0} discard-index!
  "Release the lock of a `stage-files!` result without touching the index."
  [{:keys [lock]}]
  (when lock
    (io/delete-file lock true)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} index-paths
  "`{:index :lock}`: absolute paths of the index git uses in
   `worktree-path` and of its lock file, from `git rev-parse
   --absolute-git-dir`; nil when git cannot say."
  [worktree-path environment]
  (let [r (process/sh {:dir (str worktree-path) :env (git-env environment nil)}
                      "git" "rev-parse" "--absolute-git-dir")]
    (when (zero? (:exit r))
      (let [git-dir (str/trim-newline (str (:out r)))]
        {:index (str (io/file git-dir "index"))
         :lock  (str (io/file git-dir "index.lock"))}))))

(defn ^{:stratum 1} git-exit
  "Exit code of `git <hooks-disabled-args> <args>` in `worktree-path`,
   with GIT_INDEX_FILE set to `index`."
  [worktree-path environment index args]
  (:exit (apply process/shell
                {:dir (str worktree-path) :continue true :out :string :err :string
                 :env (git-env environment index)}
                "git" (concat git-dir/hooks-disabled-args args))))

(defn ^{:stratum 1} stage-file!
  "Stage one fix file into the index at `index`: `git add` for a write,
   `git rm --cached --ignore-unmatch` for a delete (a no-op when git never
   tracked it). Returns nil, or an error message."
  [worktree-path environment index {:keys [path action]}]
  (let [args (if (= :delete action)
               ["rm" "--cached" "--quiet" "--ignore-unmatch" "--" path]
               ["add" "--" path])
        r    (apply process/shell
                    {:dir (str worktree-path) :continue true :out :string :err :string
                     :env (git-env environment index)}
                    "git" (concat git-dir/hooks-disabled-args ["--literal-pathspecs"] args))]
    (when-not (zero? (:exit r))
      (str path ": " (str/trim (str (:err r)))))))

(defn ^{:stratum 1} install-index!
  "Rename the lock file of a `stage-files!` result over the index, after
   the commit from it succeeded; that releases the lock. Returns nil, or
   an error message (the lock is then deleted)."
  [{:keys [index lock] :as staged}]
  (try+
   (java.nio.file.Files/move (.toPath (io/file lock)) (.toPath (io/file index))
                             (into-array java.nio.file.CopyOption
                                         [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                          java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
   nil
   (catch Exception e
     (discard-index! staged)
     (.getMessage e))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stage-files!
  "Take git's index lock in `worktree-path`, fill it from HEAD, and stage
   `files` (an artifact's `:code/files`: `:path`, `:action`) into the lock
   file. Refuses when the index already has staged changes. Returns
   `{:index :lock}` with the lock held, for the commit (GIT_INDEX_FILE =
   `:lock`) and then `install-index!` or `discard-index!`; or
   `{:error msg}` with the lock released, or never taken when another
   process holds it. The index itself is not written. `environment`
   defaults to this process's."
  ([worktree-path files]
   (stage-files! worktree-path files (System/getenv)))
  ([worktree-path files environment]
   (try+
    (if-let [{:keys [index lock] :as paths} (index-paths worktree-path environment)]
      (cond
        (not (.exists (io/file index)))
        {:error "the worktree has no index"}

        (not (try+ (java.nio.file.Files/createFile (.toPath (io/file lock))
                                                   (make-array java.nio.file.attribute.FileAttribute 0))
                   true
                   (catch java.nio.file.FileAlreadyExistsException _ false)))
        {:error (str lock " exists: another git process is using the index")}

        :else
        (try+
         (let [diff  (git-exit worktree-path environment index
                               ["diff-index" "--cached" "--quiet" "HEAD" "--"])
               error (cond
                       (= 1 diff)
                       (str "the index already has staged changes; the fix loop commits only"
                            " its own files and will not include or discard them")

                       (not (zero? diff))
                       "git cannot compare the index with HEAD"

                       :else
                       (do (io/copy (io/file index) (io/file lock))
                           (if (zero? (git-exit worktree-path environment lock ["read-tree" "HEAD"]))
                             (some #(stage-file! worktree-path environment lock %) files)
                             "git cannot read HEAD into the index lock")))]
           (if error
             (do (discard-index! paths)
                 {:error error})
             paths))
         (catch Exception e
           (discard-index! paths)
           {:error (.getMessage e)})))
      {:error "git cannot locate the worktree's git dir"})
    (catch Exception e
      {:error (.getMessage e)}))))
