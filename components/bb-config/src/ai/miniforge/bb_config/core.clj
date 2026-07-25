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
(ns ai.miniforge.bb-config.core
  "Config loader implementation.

   Stratification (intra-namespace):
   Layer 0 — `default-filename` and `read-edn` (no in-ns deps).
   Layer 1 — `default-path` (composes Layer 0).
   Layer 2 — `load` (composes Layer 0 + Layer 1).
   Layer 3 — `get` (composes Layer 1 + Layer 2)."
  (:refer-clojure :exclude [load get])
  (:require [ai.miniforge.bb-paths.interface :as paths]
            [babashka.fs :as fs]
            [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:const default-filename "bb-tasks.edn")

;; No in-namespace dependencies.
(defn ^{:stratum 0} read-edn
  "Parse `path` as EDN. Returns `{}` if the file is absent."
  [path]
  (if (fs/exists? path)
    (edn/read-string (slurp path))
    {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} default-path
  "Absolute path to `bb-tasks.edn` under the current repo root."
  []
  (str (paths/repo-root) "/" default-filename))

;------------------------------------------------------------------------------ Layer 2

;; Composes Layer 0 + Layer 1.
(defn ^{:stratum 2} load
  "Load the whole config map."
  ([] (read-edn (default-path)))
  ([path] (read-edn path)))

;------------------------------------------------------------------------------ Layer 3

;; Composes Layer 1 (`default-path`) + Layer 2 (`load`).
(defn ^{:stratum 3} get
  "Return the config slice for `task-key`."
  ([task-key] (get (default-path) task-key))
  ([source task-key]
   (clojure.core/get (if (map? source) source (load source)) task-key)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (read-edn (default-path))
  (get :generate-icon)

  :leave-this-here)
