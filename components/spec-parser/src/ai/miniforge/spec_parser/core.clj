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
   Layer 2: Spec normalization — canonical :spec/* only

   Failure model: layer 0/1/2 fns return canonical anomaly maps
   (`ai.miniforge.anomaly.interface/anomaly`) on caller-supplied input
   problems. The single boundary fn `parse-spec-file` escalates anomalies
   to `ex-info` so existing slingshot callers (CLI `run` command,
   task-executor pre-flight) keep their exception-shaped contract."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.knowledge.interface :as knowledge]
   [ai.miniforge.spec-parser.schema :as schema]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File format detection

(defn detect-format
  "Detect file format from extension.

   Returns the format keyword on success, or an `:invalid-input` anomaly
   when the extension is not in the supported set."
  [path]
  (let [ext (fs/extension path)]
    (case ext
      "yaml" :yaml
      "yml"  :yaml
      "edn"  :edn
      "json" :json
      "md"   :markdown
      (anomaly/anomaly :invalid-input
                       (str "Unsupported file format: " ext)
                       {:path path
                        :extension ext
                        :supported #{"yaml" "yml" "edn" "json" "md"}}))))

;------------------------------------------------------------------------------ Layer 1
;; Format-specific parsers

(defn parse-yaml
  "Parse YAML file content.

   Returns an `:unsupported` anomaly — YAML support is not yet
   implemented; callers should convert their spec to EDN, JSON, or
   Markdown."
  [_content]
  (anomaly/anomaly :unsupported
                   "YAML support coming soon - use EDN or JSON for now"
                   {:format :yaml
                    :workaround "Convert your YAML to EDN or JSON"}))

(defn parse-edn
  "Parse EDN file content.

   Returns the parsed value on success, or an `:invalid-input` anomaly
   when the content is malformed."
  [content]
  (try
    (edn/read-string content)
    (catch Exception e
      (anomaly/anomaly :invalid-input
                       "Failed to parse EDN file"
                       {:error (ex-message e)}))))

(defn parse-json
  "Parse JSON file content.

   Returns the parsed value on success, or an `:invalid-input` anomaly
   when the content is malformed."
  [content]
  (try
    (json/parse-string content true)
    (catch Exception e
      (anomaly/anomaly :invalid-input
                       "Failed to parse JSON file"
                       {:error (ex-message e)}))))

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

(defn- h1-title
  "Extract the first H1 heading text from a markdown body, or nil if absent."
  [body]
  (when-let [[_ title] (re-find #"(?m)^#\s+(.+)$" body)]
    (str/trim title)))

(defn- body-without-h1
  "Remove the first H1 heading line from a markdown body."
  [body]
  (str/trim (str/replace-first body #"(?m)^#[^\n]*\n?" "")))

(defn- decorate-from-body
  "Synthesize a :spec/* map from a plain markdown document with no frontmatter.
   Title is inferred from the first H1; the remainder becomes the description."
  [body]
  (let [title (or (h1-title body) "Untitled")]
    {:spec/title       title
     :spec/description (let [remainder (body-without-h1 body)]
                         (if (str/blank? remainder) title remainder))}))

(defn parse-markdown
  "Parse Markdown file with optional YAML frontmatter.

   With frontmatter: structured fields are namespaced (:title → :spec/title)
   and the body is appended to :spec/description for full agent context.

   Without frontmatter: title is inferred from the first H1 heading and the
   remaining body becomes :spec/description. No error is raised — the document
   is decorated automatically so any design doc can be fed directly to the
   workflow engine."
  [content]
  (if-let [parsed (knowledge/split-frontmatter content)]
    (let [raw-fm (knowledge/parse-yaml-frontmatter (:frontmatter parsed))
          fm     (namespace-frontmatter-keys raw-fm)
          body   (:body parsed)]
      (cond-> fm
        (and body (not (str/blank? body)))
        (update :spec/description #(str % "\n\n" (str/trim body)))))
    ;; No frontmatter — synthesize spec metadata from document structure
    (decorate-from-body content)))

(def format-parsers
  "Registry of format -> parser-fn. Extend this to add new formats."
  {:yaml     parse-yaml
   :edn      parse-edn
   :json     parse-json
   :markdown parse-markdown})

(defn parse-content
  "Parse file content using the appropriate format parser.

   Returns the parser's result (which may itself be an anomaly when the
   content is malformed), or a `:fault` anomaly when no parser is
   registered for the requested format. The fault case is exhaustive —
   `detect-format` only emits formats that have parsers — so reaching it
   indicates a programmer error in the registry."
  [format content]
  (if-let [parser (get format-parsers format)]
    (parser content)
    (anomaly/anomaly :fault
                     (str "No parser registered for format: " format)
                     {:format format
                      :available (keys format-parsers)})))

;------------------------------------------------------------------------------ Layer 2
;; Spec normalization — canonical :spec/* only

(defn- spec-shape-anomaly
  "Return an `:invalid-input` anomaly when `spec` violates the minimum
   shape required by `normalize-spec`, otherwise nil."
  [spec]
  (cond
    (not (map? spec))
    (anomaly/anomaly :invalid-input
                     "Workflow spec must be a map"
                     {:spec spec})

    (not (:spec/title spec))
    (anomaly/anomaly :invalid-input
                     "Workflow spec must have :spec/title"
                     {:spec spec})

    (not (:spec/description spec))
    (anomaly/anomaly :invalid-input
                     "Workflow spec must have :spec/description"
                     {:spec spec})))

(defn- assemble-normalized-spec
  "Build the normalized :spec/* payload. Caller is responsible for running
   [[spec-shape-anomaly]] first; reaching this fn with a malformed spec is
   a programmer error."
  [spec]
  (let [{:spec/keys [title description intent constraints tags
                     acceptance-criteria code-artifact
                     repo-url branch llm-backend sandbox plan-tasks]
         :workflow/keys [type version]} spec]
    (cond-> {:spec/title            title
             :spec/description      description
             :spec/intent           (or intent {:type :general})
             :spec/constraints      (or constraints [])
             :spec/tags             (or tags [])
             :spec/workflow-type    (or (some-> type keyword) :canonical-sdlc)
             :spec/workflow-version (or version "latest")
             :spec/raw-data         spec}
      acceptance-criteria (assoc :spec/acceptance-criteria acceptance-criteria)
      code-artifact       (assoc :spec/code-artifact code-artifact)
      repo-url            (assoc :spec/repo-url repo-url)
      branch              (assoc :spec/branch branch)
      llm-backend         (assoc :spec/llm-backend llm-backend)
      (some? sandbox)     (assoc :spec/sandbox sandbox)
      plan-tasks          (assoc :spec/plan-tasks plan-tasks))))

(defn normalize-spec
  "Normalize parsed spec to canonical :spec/* workflow format.

   Accepts canonical :spec/* input and applies defaults for missing
   optional fields. Returns the normalized payload on success, or an
   `:invalid-input` anomaly when the input is not a map or is missing
   :spec/title or :spec/description."
  [spec]
  (or (spec-shape-anomaly spec)
      (assemble-normalized-spec spec)))

;------------------------------------------------------------------------------ Layer 3
;; File loading + boundary escalation

(defn- escalate!
  "Boundary helper — translate a canonical anomaly into the legacy
   ex-info shape so existing slingshot callers (CLI `run`, task-executor
   pre-flight) keep their exception contract.

   `parse-spec-file` is the single escalation point in this component;
   layer 0/1/2 fns return anomalies."
  [anom]
  (throw (ex-info (:anomaly/message anom)
                  (assoc (:anomaly/data anom)
                         :anomaly/type (:anomaly/type anom)))))

(defn- file-not-found-anomaly
  "Return a `:not-found` anomaly when `path` does not resolve, otherwise nil."
  [path]
  (when-not (fs/exists? path)
    (anomaly/anomaly :not-found
                     (str "Spec file not found: " path)
                     {:path path})))

(defn parse-spec-file
  "Parse a workflow specification file and normalize to canonical format.

   Supported formats:
   - .edn      - Clojure EDN (recommended)
   - .json     - JSON
   - .md       - Markdown with YAML frontmatter
   - .yaml/.yml - YAML (coming soon)

   Returns normalized spec map ready for workflow engine, decorated with:
   - :spec/provenance - Source file metadata

   Boundary semantics: this is the public escalation point for the
   component. Anomalies surfaced by `detect-format`, `parse-content`,
   or `normalize-spec` are rethrown as ex-info so existing slingshot
   callers (CLI `run`, task-executor pre-flight) keep their existing
   exception-shaped contract.

   Throws ex-info on:
   - File not found
   - Unsupported format
   - Parse errors
   - Invalid spec schema"
  [path]
  (when-let [anom (file-not-found-anomaly path)]
    (escalate! anom))

  (let [format (detect-format path)
        _      (when (anomaly/anomaly? format) (escalate! format))
        content    (slurp path)
        parsed     (parse-content format content)
        _          (when (anomaly/anomaly? parsed) (escalate! parsed))
        normalized (normalize-spec parsed)
        _          (when (anomaly/anomaly? normalized) (escalate! normalized))]

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
