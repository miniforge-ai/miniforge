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

(ns ai.miniforge.spec-parser.core
  "Spec normalization and format-specific parsers.

   Layer 0: File format detection
   Layer 1: Format-specific parsers (EDN, JSON, Markdown)
   Layer 2: Spec normalization — canonical :spec/* only"
  (:require
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.spec-parser.schema :as schema]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File format detection

(defn detect-format
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

(defn parse-yaml
  "Parse YAML file content."
  [_content]
  (throw (ex-info "YAML support coming soon - use EDN or JSON for now"
                  {:format :yaml
                   :workaround "Convert your YAML to EDN or JSON"})))

(defn parse-edn
  "Parse EDN file content."
  [content]
  (try
    (edn/read-string content)
    (catch Exception e
      (throw (ex-info "Failed to parse EDN file"
                      {:error (ex-message e)} e)))))

(defn parse-json
  "Parse JSON file content."
  [content]
  (try
    (json/parse-string content true)
    (catch Exception e
      (throw (ex-info "Failed to parse JSON file"
                      {:error (ex-message e)} e)))))

(def ^:private spec-key->ns
  "Map plain YAML keys to their :spec/* or :workflow/* namespace.
   The YAML parser produces unnamespaced keywords; normalize-spec requires
   namespaced ones.

   Both hyphenated (:acceptance-criteria) and underscore (:acceptance_criteria)
   forms are listed because the YAML parser regex only captures \\w+ (no hyphens),
   so frontmatter authors must use underscores for multi-word keys."
  {:title                "spec"
   :description          "spec"
   :intent               "spec"
   :constraints          "spec"
   :tags                 "spec"
   :acceptance-criteria  "spec"
   :acceptance_criteria  "spec"   ; underscore form from YAML parser
   :code-artifact        "spec"
   :code_artifact        "spec"
   :repo-url             "spec"
   :repo_url             "spec"
   :branch               "spec"
   :llm-backend          "spec"
   :llm_backend          "spec"
   :sandbox              "spec"
   :plan-tasks           "spec"
   :plan_tasks           "spec"
   :type                 "workflow"
   :version              "workflow"})

(defn- namespace-frontmatter-keys
  "Remap plain YAML keys to :spec/* / :workflow/* namespaces for normalize-spec.
   Underscore key names (from YAML parser limitation) are normalized to hyphens
   so :acceptance_criteria becomes :spec/acceptance-criteria."
  [m]
  (reduce-kv (fn [acc k v]
               (if-let [ns (get spec-key->ns k)]
                 (let [canonical (str/replace (name k) "_" "-")]
                   (assoc acc (keyword ns canonical) v))
                 (assoc acc k v)))
             {} m))

(defn parse-markdown
  "Parse Markdown file with YAML frontmatter.
   Frontmatter contains structured spec data; body is appended to description."
  [content]
  (let [parsed (knowledge/split-frontmatter content)]
    (when-not parsed
      (throw (ex-info "Markdown spec requires YAML frontmatter (---...---)"
                      {:hint "Add frontmatter with at least title and description."})))
    (let [raw-fm   (knowledge/parse-yaml-frontmatter (:frontmatter parsed))
          fm       (namespace-frontmatter-keys raw-fm)
          body     (:body parsed)]
      ;; Append body prose to description so agents get full context without
      ;; needing to re-read the source file.
      (cond-> fm
        (and body (not (str/blank? body)))
        (update :spec/description
                #(str % "\n\n" (str/trim body)))))))

(def format-parsers
  "Registry of format -> parser-fn. Extend this to add new formats."
  {:yaml     parse-yaml
   :edn      parse-edn
   :json     parse-json
   :markdown parse-markdown})

(defn parse-content
  "Parse file content using the appropriate format parser."
  [format content]
  (if-let [parser (get format-parsers format)]
    (parser content)
    (throw (ex-info (str "No parser registered for format: " format)
                    {:format format
                     :available (keys format-parsers)}))))

;------------------------------------------------------------------------------ Layer 2
;; Spec normalization — canonical :spec/* only

(defn normalize-spec
  "Normalize parsed spec to canonical :spec/* workflow format.

   Accepts canonical :spec/* input and applies defaults for missing optional fields.
   Uses destructuring for clean extraction.

   Output: normalized map with :spec/* namespace ready for workflow engine."
  [{:spec/keys [title description intent constraints tags
                acceptance-criteria code-artifact
                repo-url branch llm-backend sandbox plan-tasks]
    :workflow/keys [type version]
    :as spec}]
  (when-not (map? spec)
    (throw (ex-info "Workflow spec must be a map"
                    {:spec spec})))

  (when-not title
    (throw (ex-info "Workflow spec must have :spec/title"
                    {:spec spec})))

  (when-not description
    (throw (ex-info "Workflow spec must have :spec/description"
                    {:spec spec})))

  ;; Build normalized payload with defaults
  (cond-> {:spec/title            title
           :spec/description      description
           :spec/intent           (or intent {:type :general})
           :spec/constraints      (or constraints [])
           :spec/tags             (or tags [])
           :spec/workflow-type    (or type :full-sdlc)
           :spec/workflow-version (or version "latest")
           :spec/raw-data         spec}
    acceptance-criteria (assoc :spec/acceptance-criteria acceptance-criteria)
    code-artifact       (assoc :spec/code-artifact code-artifact)
    repo-url            (assoc :spec/repo-url repo-url)
    branch              (assoc :spec/branch branch)
    llm-backend         (assoc :spec/llm-backend llm-backend)
    (some? sandbox)     (assoc :spec/sandbox sandbox)
    plan-tasks          (assoc :spec/plan-tasks plan-tasks)))

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical format.

   Supported formats:
   - .edn      - Clojure EDN (recommended)
   - .json     - JSON
   - .md       - Markdown with YAML frontmatter
   - .yaml/.yml - YAML (coming soon)

   Returns normalized spec map ready for workflow engine, decorated with:
   - :spec/provenance - Source file metadata

   Throws ex-info on:
   - File not found
   - Unsupported format
   - Parse errors
   - Invalid spec schema"
  [path]
  (when-not (fs/exists? path)
    (throw (ex-info (str "Spec file not found: " path)
                    {:path path})))

  (let [format     (detect-format path)
        content    (slurp path)
        parsed     (parse-content format content)
        normalized (normalize-spec parsed)]

    ;; Add provenance decoration
    (assoc normalized
           :spec/provenance
           {:source-file (str path)
            :source-format format
            :loaded-at (java.util.Date.)
            :file-size (.length (fs/file path))})))

(defn validate-spec
  "Validate a normalized spec using the Malli SpecPayload schema.

   Returns:
   - {:valid? true} if valid
   - {:valid? false :errors [...]} if invalid"
  [spec]
  (if (schema/valid-spec-payload? spec)
    {:valid? true}
    {:valid? false :errors (schema/explain-spec-payload spec)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example: Parse EDN spec
  (parse-spec-file "examples/workflows/simple-refactor.edn")

  ;; Example: Validate a normalized spec
  (validate-spec {:spec/title "T"
                  :spec/description "D"
                  :spec/intent {:type :general}
                  :spec/constraints []
                  :spec/tags []
                  :spec/workflow-version "latest"
                  :spec/raw-data {}})

  :leave-this-here)
