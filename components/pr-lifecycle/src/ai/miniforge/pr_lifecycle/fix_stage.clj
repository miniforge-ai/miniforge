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
  "Stage a fix's files into a temporary index, so a failure leaves the
   worktree's real index as it was.

   `fix-loop/commit-fix` copies the current index beside it, stages
   exactly the fix's files into the copy (GIT_INDEX_FILE), commits from
   the copy, and only then moves it over the real index. A staging or
   commit failure deletes the copy, so a retry never commits what an
   earlier attempt staged. Every git command here runs with hooks
   disabled (`git-dir/hooks-disabled-args`; `git add` can run
   post-index-change) and literal pathspecs, so a name with glob
   characters stages only itself."
  (:require
   [ai.miniforge.pr-lifecycle.policy-eval.git-dir :as git-dir]
   [babashka.process :as process]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} index-file
  "Absolute path of the index git uses in `worktree-path`, or nil when
   git cannot say."
  [worktree-path]
  (let [r (process/sh {:dir (str worktree-path)} "git" "rev-parse" "--git-path" "index")]
    (when (zero? (:exit r))
      (let [f (io/file (str/trim-newline (str (:out r))))]
        (str (if (.isAbsolute f) f (io/file (str worktree-path) (str f))))))))

(defn ^{:stratum 0} stage-file!
  "Stage one fix file into the index at `index`: `git add` for a write,
   `git rm --cached --ignore-unmatch` for a delete (a no-op when git never
   tracked it). Returns nil, or an error message."
  [worktree-path index {:keys [path action]}]
  (let [args (if (= :delete action)
               ["rm" "--cached" "--quiet" "--ignore-unmatch" "--" path]
               ["add" "--" path])
        r    (apply process/shell
                    {:dir (str worktree-path) :continue true :out :string :err :string
                     :extra-env {"GIT_INDEX_FILE" index}}
                    "git" (concat git-dir/hooks-disabled-args ["--literal-pathspecs"] args))]
    (when-not (zero? (:exit r))
      (str path ": " (str/trim (str (:err r)))))))

(defn ^{:stratum 0} discard-index!
  "Delete the temporary index of a `stage-files!` result."
  [{:keys [temp]}]
  (when temp
    (io/delete-file temp true)))

(defn ^{:stratum 0} install-index!
  "Move the temporary index of a `stage-files!` result over the real one,
   after the commit from it succeeded. Returns nil, or an error message."
  [{:keys [index temp]}]
  (try+
   (java.nio.file.Files/move (.toPath (io/file temp)) (.toPath (io/file index))
                             (into-array java.nio.file.CopyOption
                                         [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                          java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
   nil
   (catch Exception e
     (.getMessage e))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stage-files!
  "Copy the worktree's index to a temporary file beside it and stage
   `files` (an artifact's `:code/files`: `:path`, `:action`) into the copy.
   Returns `{:index :temp}`, or `{:error msg}` after deleting the copy;
   the real index is never touched."
  [worktree-path files]
  (try+
   (if-let [index (index-file worktree-path)]
     (let [temp {:temp (str index ".fix-" (random-uuid))}]
       (try+
        (when (.exists (io/file index))
          (io/copy (io/file index) (io/file (:temp temp))))
        (if-let [error (some #(stage-file! worktree-path (:temp temp) %) files)]
          (do (discard-index! temp)
              {:error error})
          (assoc temp :index index))
        (catch Exception e
          (discard-index! temp)
          {:error (.getMessage e)})))
     {:error "git cannot locate the worktree's index"})
   (catch Exception e
     {:error (.getMessage e)})))
