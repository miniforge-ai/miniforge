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

(ns ai.miniforge.cli.spec-parser
  "Parse workflow specification files (YAML, EDN, JSON).

   Converts user-provided workflow specs into the canonical workflow format
   expected by the workflow engine."
  (:require
   [ai.miniforge.knowledge.interface :as knowledge]
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
      "md"   :markdown
      (throw (ex-info (str "Unsupported file format: " ext)
                      {:path path :extension ext})))))

;------------------------------------------------------------------------------ Layer 1
;; Format-specific parsers

(defn- parse-yaml
  "Parse YAML file content."
  [_content]
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

(defn- parse-markdown
  "Parse Markdown file with YAML frontmatter.
   Frontmatter contains structured spec data, body is optional context."
  [content]
  (let [parsed (knowledge/split-frontmatter content)]
    (when-not parsed
      (throw (ex-info "Markdown file must have YAML frontmatter (---)"
                      {:hint "Add frontmatter with title, description, etc."})))
    (let [frontmatter (knowledge/parse-yaml-frontmatter (:frontmatter parsed))
          body (:body parsed)]
      ;; If there's body content beyond title, use it to extend description
      (cond-> frontmatter
        (and body (not (str/blank? body)))
        (update :description
                #(str % "\n\n" (str/trim body)))))))

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
   :spec/tags (or (:tags spec) [])
   ;; Preserve workflow metadata for custom workflow selection
   ;; Don't set a default - let workflow selector/recommender decide
   :spec/workflow-type (:workflow/type spec)
   :spec/workflow-version (or (:workflow/version spec) "latest")
   ;; Pass through all other spec keys (like :task/*)
   :spec/raw-data spec})

;------------------------------------------------------------------------------ Layer 3
;; Public API

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical format.

   Supported formats:
   - .edn      - Clojure EDN (recommended)
   - .json     - JSON
   - .md       - Markdown with YAML frontmatter
   - .yaml/.yml - YAML (coming soon)

   Returns normalized spec map ready for workflow engine, decorated with:
   - :spec/provenance - Source file metadata (Layer 0 decoration)

   Example EDN spec:
     {:title \"Refactor logging component\"
      :description \"Extract structured logging to separate component\"
      :intent {:type :refactor
               :scope [\"components/logging/src\"]}
      :constraints [\"no-breaking-changes\"
                    \"maintain-test-coverage\"]}

   Example Markdown spec:
     ---
     title: Refactor logging component
     description: Extract structured logging to separate component
     intent:
       type: refactor
       scope: [components/logging/src]
     constraints: [no-breaking-changes, maintain-test-coverage]
     ---

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
                 :yaml     (parse-yaml content)
                 :edn      (parse-edn content)
                 :json     (parse-json content)
                 :markdown (parse-markdown content))
        normalized (normalize-spec parsed)]

    ;; Layer 0 decoration: Add provenance at parse time
    (assoc normalized
           :spec/provenance
           {:source-file (str path)
            :source-format format
            :loaded-at (java.util.Date.)
            :file-size (.length (fs/file path))})))

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
