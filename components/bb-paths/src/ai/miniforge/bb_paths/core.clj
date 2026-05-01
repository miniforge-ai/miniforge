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

(ns ai.miniforge.bb-paths.core
  "Path helpers. Repo-root is discovered by walking up from cwd until a
   `bb.edn` is found — the same marker the umbrella products use, so
   tasks and their tests see the same root regardless of where `bb` was
   invoked.

   Stratification (intra-namespace dependencies only):
   Layer 0 — no in-namespace deps. `find-up` and the side-effect
             helpers (`ensure-dir!`, `tmp-dir!`, `delete-tree!`) only
             call out to `babashka.fs`.
   Layer 1 — composes Layer 0. `repo-root` drives `find-up`.
   Layer 2 — composes Layer 1. `under-root` rests on `repo-root`."
  (:require [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0
;; No in-namespace dependencies — only `babashka.fs` and built-ins.

(defn- find-up
  "Walk up from `start` until a directory containing `marker` is found.
   Returns the containing directory as a string, or nil if none found."
  [start marker]
  (loop [dir (fs/absolutize start)]
    (cond
      (nil? dir)                        nil
      (fs/exists? (fs/path dir marker)) (str dir)
      :else                             (recur (fs/parent dir)))))

(defn ensure-dir!
  "Create `path` (and parents) if it does not exist. Returns the path
   as a string."
  [path]
  (fs/create-dirs path)
  (str path))

(defn tmp-dir!
  "Create a fresh temp directory with `prefix`. Returns its absolute
   path as a string."
  [prefix]
  (str (fs/create-temp-dir {:prefix prefix})))

(defn delete-tree!
  "Recursively delete `path` if it exists. No-op if nil or missing."
  [path]
  (when (and path (fs/exists? path))
    (fs/delete-tree path)))

;------------------------------------------------------------------------------ Layer 1
;; Composes Layer 0.

(defn repo-root
  "Absolute path to the repo root (nearest `bb.edn` above the cwd).
   Throws ex-info if no `bb.edn` is found walking up from cwd."
  []
  (or (find-up (fs/cwd) "bb.edn")
      (throw (ex-info "Could not locate bb.edn from cwd"
                      {:cwd (str (fs/cwd))}))))

;------------------------------------------------------------------------------ Layer 2
;; Composes Layer 1.

(defn under-root
  "Resolve `segments` beneath the repo root. Returns an absolute path
   string."
  [& segments]
  (str (apply fs/path (repo-root) segments)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (repo-root)
  (under-root "bb.edn")
  (let [d (tmp-dir! "bb-paths-scratch-")]
    (ensure-dir! (str d "/a/b"))
    (delete-tree! d))

  :leave-this-here)
