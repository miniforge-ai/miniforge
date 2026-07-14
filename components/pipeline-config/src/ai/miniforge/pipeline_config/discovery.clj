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

(ns ai.miniforge.pipeline-config.discovery
  "Find pipeline EDN files on disk.
   NOTE: Pipeline files are currently loaded unsigned. A signing/verification
   mechanism should be added before loading untrusted pipeline definitions
   to mitigate injection risk."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline file discovery primitives

(defn- pipeline-file?
  "Return true if the file looks like a pipeline definition."
  [^java.io.File f]
  (and (.isFile f)
       (.endsWith (.getName f) ".edn")))

(defn- read-pipeline-header
  "Parse a pipeline EDN file and return a map of :path, :name, and :version.
   Returns nil when the file cannot be read or parsed, or lacks :pipeline/name."
  [^java.io.File f]
  (try
    (let [content (edn/read-string (slurp f))]
      (when (and (map? content) (:pipeline/name content))
        {:path    (.getAbsolutePath f)
         :name    (:pipeline/name content)
         :version (:pipeline/version content)}))
    (catch Exception _
      nil)))

(defn- existing-directory
  "Return a directory file for `path`, or nil when it is not a directory."
  [path]
  (let [f (io/file path)]
    (when (.isDirectory f)
      f)))

(defn- pipeline-files-in-dir
  "Return pipeline EDN files found under dir."
  [dir]
  (->> (file-seq dir)
       (filter pipeline-file?)))

;------------------------------------------------------------------------------ Layer 1
;; Public discovery operation

(defn discover-pipelines
  "Scan directories for pipeline EDN files.
   Returns schema/success with :pipelines key.
   Files that cannot be read or parsed, or that lack :pipeline/name, are excluded."
  [search-paths]
  (let [dirs    (keep existing-directory search-paths)
        files   (mapcat pipeline-files-in-dir dirs)
        headers (into [] (keep read-pipeline-header) files)]
    (schema/success :pipelines headers)))
