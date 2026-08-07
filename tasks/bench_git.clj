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
(ns bench-git
  "Git plumbing the bench tasks read the world through. Every fn here is
   total: a failing git call, or a `dir` that does not exist, comes back
   as a value rather than a throw, because `bench/verify` is routinely
   handed a path an operator typed."
  (:require
   [babashka.process :as p]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

;; No in-namespace dependencies.
(defn ^{:stratum 0} git
  "Run git with `args` in `dir`. Returns the process result map. A `dir`
   that does not exist makes the process launch itself fail, which is
   reported as a non-zero exit like any other git failure."
  [dir & args]
  (try
    (apply p/sh {:dir dir :out :string :err :string :continue true} "git" args)
    (catch Exception e
      {:exit 127 :out "" :err (.getMessage e)})))

(defn ^{:stratum 0} absolute-path
  "Canonical absolute path of `path`, resolved against `dir` when it is
   relative. `git rev-parse --git-common-dir` returns a bare `.git` for
   the primary worktree and an absolute path for linked ones, so both
   shapes have to normalize before they can be compared."
  [dir path]
  (let [f (File. (str path))]
    (.getCanonicalPath (if (.isAbsolute f) f (File. (str dir) (str path))))))

;------------------------------------------------------------------------------ Layer 1

;; Composes Layer 0.
(defn ^{:stratum 1} git-out
  "Trimmed stdout of a git command, or nil when it failed or said nothing."
  [dir & args]
  (let [{:keys [exit out]} (apply git dir args)]
    (when (zero? exit)
      (not-empty (str/trim (str out))))))

;------------------------------------------------------------------------------ Layer 2

;; Composes Layer 1.
(defn ^{:stratum 2} remote-url
  "Configured URL of `remote` in `dir`, or nil when unset."
  [dir remote]
  (git-out dir "remote" "get-url" remote))

(defn ^{:stratum 2} git-dirs
  "The `:git-dir` (per-worktree) and `:common-dir` (shared) of `dir`, both
   canonical. They are equal for a standalone clone and differ for a
   linked worktree. Returns nil when `dir` is not a git checkout."
  [dir]
  (when-let [git-dir (git-out dir "rev-parse" "--absolute-git-dir")]
    {:git-dir (absolute-path dir git-dir)
     :common-dir (absolute-path dir (or (git-out dir "rev-parse" "--git-common-dir")
                                        git-dir))}))
