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
(ns ai.miniforge.cli.worktree.root-resolution
  "Walk upward from a start path to the nearest directory carrying a `.git`
   marker. Split out of `ai.miniforge.cli.worktree` (rule 210: the parent
   namespace measured 5 layers, max 3; this directory walk is
   layer-coherent on its own). `ai.miniforge.cli.worktree/worktree-root`
   calls `nearest-git-root` first, falling back to `git rev-parse` only
   when no marker is found."
  (:require
   [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

;; Path resolution
(def ^{:stratum 0} ^:private git-marker-name
  ".git")

(defn- ^{:stratum 0} file->dir
  [path]
  (let [file (fs/file path)]
    (if (fs/directory? file)
      file
      (fs/parent file))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} canonical-dir
  "Canonicalized directory string for `path`; a file path resolves to its
   parent directory. Nil-safe. Public because `worktree-root` in the parent
   namespace calls it across the namespace boundary."
  [path]
  (some-> path file->dir fs/canonicalize str))

(defn- ^{:stratum 1} git-marker-path
  [dir]
  (fs/path dir git-marker-name))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} nearest-git-root
  "Walk upward from start-path and return the nearest directory containing
   a .git file or directory. Returns nil when no checkout is found."
  ([] (nearest-git-root (System/getProperty "user.dir")))
  ([start-path]
   (loop [dir (canonical-dir start-path)]
     (when dir
       (let [git-path (git-marker-path dir)
             parent (some-> dir fs/parent str)]
         (cond
           (fs/exists? git-path) dir
           (or (nil? parent) (= dir parent)) nil
           :else (recur parent)))))))
