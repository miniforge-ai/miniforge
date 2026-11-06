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
(ns ai.miniforge.gate.policy-pack.worktree
  "Reads a changed file's entry from the execution worktree, and never from
   outside it."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} path-inside-worktree?
  [worktree file]
  (let [root-path (str (.getPath worktree) java.io.File/separator)
        file-path (.getPath file)]
    (or (= file-path (.getPath worktree))
        (str/starts-with? file-path root-path))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} safe-worktree-file
  [worktree-path file-path]
  (let [worktree (.getCanonicalFile (io/file worktree-path))
        file     (.getCanonicalFile (io/file worktree file-path))]
    (when (path-inside-worktree? worktree file)
      file)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} code-file-entry
  [worktree-path file-path action]
  (let [file    (safe-worktree-file worktree-path file-path)
        content (cond
                  (= :delete action) ""
                  (and file (.isFile file)) (slurp file))]
    (cond-> {:path file-path}
      content (assoc :content content)
      action  (assoc :action action))))
