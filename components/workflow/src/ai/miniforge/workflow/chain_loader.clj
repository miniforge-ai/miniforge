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

(ns ai.miniforge.workflow.chain-loader
  "Chain definition loading from classpath resources.
   Loads chain EDN files from resources/chains/ directory."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]))

;------------------------------------------------------------------------------ Layer 0
;; File helpers

(defn- edn-file?
  "True when file is an existing .edn file."
  [^java.io.File f]
  (and (.isFile f) (.endsWith (.getName f) ".edn")))

(defn- matches-prefix?
  "True when filename starts with prefix."
  [prefix ^java.io.File f]
  (.startsWith (.getName f) prefix))

(defn- resource-dir-files
  "List files in a classpath resource directory, or nil."
  [dir-name]
  (when-let [dir-url (io/resource dir-name)]
    (let [dir-file (io/file (.getPath dir-url))]
      (when (.isDirectory dir-file)
        (vec (.listFiles dir-file))))))

(defn- parse-chain-file
  "Parse a chain EDN file into a summary map, or nil on failure."
  [^java.io.File f]
  (try
    (let [content (edn/read-string (slurp f))]
      {:id (:chain/id content)
       :version (:chain/version content)
       :description (:chain/description content)
       :steps (count (:chain/steps content))})
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Resource loading

(defn- find-latest-chain-resource
  "Find the highest-versioned chain EDN resource path for chain-id."
  [chain-id]
  (let [prefix (str (name chain-id) "-v")]
    (some->> (resource-dir-files "chains")
             (filter edn-file?)
             (filter (partial matches-prefix? prefix))
             (sort-by #(.getName %) (comp - compare))
             first
             (.getName)
             (str "chains/"))))

(defn- load-chain-resource
  "Load a chain EDN file from classpath."
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (edn/read-string (slurp resource))))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn load-chain
  "Load a chain definition from classpath resources.

   Arguments:
   - chain-id: Chain identifier (keyword, e.g. :spec-to-pr)
   - version: Version string (e.g. \"1.0.0\" or \"latest\")

   Returns chain definition map or throws if not found."
  [chain-id version]
  (let [versioned-path (str "chains/" (name chain-id) "-v" version ".edn")
        base-path (str "chains/" (name chain-id) ".edn")
        resource-path (or (when (and version (not= version "latest"))
                            (when (io/resource versioned-path)
                              versioned-path))
                          (when (io/resource base-path) base-path)
                          (find-latest-chain-resource chain-id))
        chain-def (when resource-path (load-chain-resource resource-path))]
    (if chain-def
      {:chain chain-def :source :resource :path resource-path}
      (throw (ex-info (str "Chain '" (name chain-id) "' not found. "
                           "Looked for: " versioned-path ", " base-path)
                      {:chain-id chain-id :version version})))))

(defn list-chains
  "List all available chain definitions from classpath."
  []
  (some->> (resource-dir-files "chains")
           (filter edn-file?)
           (keep parse-chain-file)
           vec))
