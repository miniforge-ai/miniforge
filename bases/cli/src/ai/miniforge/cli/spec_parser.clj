;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.cli.spec-parser
  "Parse workflow specification files (YAML, EDN, JSON).

   Converts user-provided workflow specs into the canonical workflow format
   expected by the workflow engine."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File format detection

(defn- detect-format
  "Detect file format from extension."
  [path]
  (let [ext (fs/extension path)]
    (case ext
      "yaml" :yaml
      "yml"  :yaml
      "edn"  :edn
      "json" :json
      (throw (ex-info (str "Unsupported file format: " ext)
                      {:path path :extension ext})))))

;------------------------------------------------------------------------------ Layer 1
;; Format-specific parsers

(defn- parse-yaml
  "Parse YAML file content."
  [content]
  ;; For now, use basic parsing that works in Babashka
  ;; In future, can add full YAML parser when needed
  (throw (ex-info "YAML support coming soon - use EDN or JSON for now"
                  {:format :yaml
                   :workaround "Convert your YAML to EDN or JSON"})))

(defn- parse-edn
  "Parse EDN file content."
  [content]
  (try
    (edn/read-string content)
    (catch Exception e
      (throw (ex-info "Failed to parse EDN file"
                      {:error (ex-message e)} e)))))

(defn- parse-json
  "Parse JSON file content."
  [content]
  (try
    (json/parse-string content true)
    (catch Exception e
      (throw (ex-info "Failed to parse JSON file"
                      {:error (ex-message e)} e)))))

;------------------------------------------------------------------------------ Layer 2
;; Spec normalization

(defn- normalize-spec
  "Normalize parsed spec to canonical workflow format.

   Input spec schema (user-provided):
     {:title \"...\"
      :description \"...\"
      :intent {:type :refactor :scope [...]}
      :constraints [...]}

   Output format (workflow engine):
     {:spec/title \"...\"
      :spec/description \"...\"
      :spec/intent {:type :refactor :scope [...]}
      :spec/constraints [...]}"
  [spec]
  (when-not (map? spec)
    (throw (ex-info "Workflow spec must be a map"
                    {:spec spec})))

  (when-not (:title spec)
    (throw (ex-info "Workflow spec must have :title"
                    {:spec spec})))

  (when-not (:description spec)
    (throw (ex-info "Workflow spec must have :description"
                    {:spec spec})))

  ;; Normalize to namespaced keys for workflow engine
  {:spec/title (:title spec)
   :spec/description (:description spec)
   :spec/intent (or (:intent spec) {:type :general})
   :spec/constraints (or (:constraints spec) [])
   :spec/tags (or (:tags spec) [])})

;------------------------------------------------------------------------------ Layer 3
;; Public API

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical format.

   Supported formats:
   - .edn  - Clojure EDN (recommended)
   - .json - JSON
   - .yaml - YAML (coming soon)

   Returns normalized spec map ready for workflow engine.

   Example EDN spec:
     {:title \"Refactor logging component\"
      :description \"Extract structured logging to separate component\"
      :intent {:type :refactor
               :scope [\"components/logging/src\"]}
      :constraints [\"no-breaking-changes\"
                    \"maintain-test-coverage\"]}

   Example JSON spec:
     {\"title\": \"Add feature\",
      \"description\": \"...\",
      \"intent\": {\"type\": \"feature\"},
      \"constraints\": [\"...\"]}

   Throws ex-info on:
   - File not found
   - Unsupported format
   - Parse errors
   - Invalid spec schema"
  [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "Spec file not found: " path)
                    {:path path})))

  (let [format (detect-format path)
        content (slurp path)
        parsed (case format
                 :yaml (parse-yaml content)
                 :edn  (parse-edn content)
                 :json (parse-json content))]

    (normalize-spec parsed)))

(defn validate-spec
  "Validate that a spec has all required fields.

   Returns:
   - {:valid? true} if valid
   - {:valid? false :errors [...]} if invalid"
  [spec]
  (let [errors (cond-> []
                 (not (:spec/title spec))
                 (conj "Missing :title field")

                 (not (:spec/description spec))
                 (conj "Missing :description field")

                 (and (:spec/intent spec)
                      (not (:type (:spec/intent spec))))
                 (conj "Intent must have :type field"))]

    (if (empty? errors)
      {:valid? true}
      {:valid? false :errors errors})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: Parse EDN spec
  (parse-spec-file "examples/workflows/simple-refactor.edn")

  ;; Example: Parse JSON spec
  (parse-spec-file "examples/workflows/add-tests.json")

  :end)
