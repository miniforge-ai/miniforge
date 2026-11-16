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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.files
  "Copying files into and out of a task worktree without escaping its
   root."
  (:require
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.file Files StandardCopyOption CopyOption]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} path-inside-worktree?
  "True when `file` (a canonicalized File) is the worktree root or is nested
   inside it. Prevents path-traversal escape via `../` segments."
  [^File worktree ^File file]
  (let [root-path (str (.getPath worktree) File/separator)
        file-path (.getPath file)]
    (or (= file-path (.getPath worktree))
        (str/starts-with? file-path root-path))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} safe-worktree-path
  "Resolve `relative-path` inside `worktree-path` and return the canonical
   File, or nil if the resolved path would escape the worktree sandbox or
   if canonicalization fails (I/O error, invalid path)."
  [worktree-path relative-path]
  (try
    (let [worktree (.getCanonicalFile (File. ^String worktree-path))
          file     (.getCanonicalFile (File. worktree ^String relative-path))]
      (when (path-inside-worktree? worktree file)
        file))
    (catch java.io.IOException _ nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} copy-file-to
  "Copy a file into the worktree."
  [worktree-path local-path remote-path]
  (let [dest (safe-worktree-path worktree-path remote-path)]
    (if-not dest
      (result/err :path-traversal (str "path escapes worktree sandbox: " remote-path))
      (try
        (let [source (File. ^String local-path)]
          (.mkdirs (.getParentFile dest))
          (Files/copy (.toPath source)
                      (.toPath dest)
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e)))))))

(defn ^{:stratum 2} copy-file-from
  "Copy a file from the worktree."
  [worktree-path remote-path local-path]
  (let [source (safe-worktree-path worktree-path remote-path)]
    (if-not source
      (result/err :path-traversal (str "path escapes worktree sandbox: " remote-path))
      (try
        (let [dest (File. ^String local-path)]
          (.mkdirs (.getParentFile dest))
          (Files/copy (.toPath source)
                      (.toPath dest)
                      (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
          (result/ok {:copied-bytes (.length source)}))
        (catch Exception e
          (result/err :copy-failed (.getMessage e)))))))
