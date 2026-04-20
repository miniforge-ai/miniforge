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

(ns ai.miniforge.bb-paths.interface
  "Path and filesystem helpers for Babashka tasks. Thin pass-through to
   `core`; all implementation lives there."
  (:require [ai.miniforge.bb-paths.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn repo-root
  "Absolute path to the repo root (nearest `bb.edn` above the cwd)."
  []
  (core/repo-root))

(defn under-root
  "Resolve `segments` beneath the repo root."
  [& segments]
  (apply core/under-root segments))

(defn ensure-dir!
  "Create `path` (and parents) if it does not exist. Returns the path."
  [path]
  (core/ensure-dir! path))

(defn tmp-dir!
  "Create a fresh temp directory with `prefix`. Returns its absolute path."
  [prefix]
  (core/tmp-dir! prefix))

(defn delete-tree!
  "Recursively delete `path` if it exists."
  [path]
  (core/delete-tree! path))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (repo-root)
  (under-root "bb.edn")

  :leave-this-here)
