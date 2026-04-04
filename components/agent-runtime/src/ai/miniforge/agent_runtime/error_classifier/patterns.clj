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

(ns ai.miniforge.agent-runtime.error-classifier.patterns
  "Error pattern definitions and matching logic.

   Provides patterns for classifying errors into three categories:
   - :agent-backend - Agent system bugs
   - :task-code - User code errors
   - :external - External service errors

   Pattern definitions are loaded from EDN configuration files in resources/error-patterns/"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;;------------------------------------------------------------------------------ Layer 0
;; Pattern loading from configuration

(defn load-pattern-config
  "Load error pattern configuration from resource file.

   Arguments:
     resource-path - Path to EDN file in resources

   Returns: Pattern config map or nil on error"
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (try
      (edn/read-string (slurp resource))
      (catch Exception _e
        nil))))

(defn compile-pattern
  "Compile a pattern map with regex string to regex object.

   Arguments:
     pattern - Map with :regex string
     type - Error type keyword
     vendor - Vendor name string

   Returns: Pattern map with compiled :regex"
  [pattern type vendor]
  (-> pattern
      (assoc :type type :vendor vendor)
      (update :regex re-pattern)))

(defn load-patterns
  "Load and compile patterns from a config file.

   Arguments:
     config-name - Name of config file (without .edn)

   Returns: Vector of compiled pattern maps"
  [config-name]
  (if-let [config (load-pattern-config (str "error-patterns/" config-name ".edn"))]
    (let [type (:type config)
          vendor (:vendor config)]
      (mapv #(compile-pattern % type vendor) (:patterns config)))
    []))

;;------------------------------------------------------------------------------ Layer 1
;; Pattern definitions (loaded from config)

(def agent-backend-patterns
  "Patterns that indicate agent system bugs (Claude Code internal errors).
   Loaded from resources/error-patterns/agent-backend.edn"
  (load-patterns "agent-backend"))

(def task-code-patterns
  "Patterns that indicate user code errors.
   Loaded from resources/error-patterns/task-code.edn"
  (load-patterns "task-code"))

(def external-patterns
  "Patterns that indicate external service errors.
   Loaded from resources/error-patterns/external.edn"
  (load-patterns "external"))

(def backend-setup-patterns
  "Patterns that indicate backend setup/configuration errors.
   Loaded from resources/error-patterns/backend-setup.edn"
  (load-patterns "backend-setup"))

;;------------------------------------------------------------------------------ Layer 2
;; Pattern matching

(defn matches-pattern?
  "Check if error message matches a pattern.

   Arguments:
     error - Error message string
     pattern - Pattern map with :regex key

   Returns: Boolean indicating if pattern matches"
  [error pattern]
  (when error
    (boolean (re-find (:regex pattern) error))))

(defn classify-by-patterns
  "Classify error by matching against pattern lists.

   Arguments:
     message - Error message string

   Returns: Pattern map with :type and :vendor, or default task-code classification"
  [message]
  (let [all-patterns (concat agent-backend-patterns
                             task-code-patterns
                             external-patterns
                             backend-setup-patterns)]
    (or (first (filter #(matches-pattern? message %) all-patterns))
        {:type :task-code
         :vendor "miniforge"})))
