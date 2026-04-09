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

(ns ai.miniforge.policy-pack.prompt-template
  "Pack-bundled prompt template interpolation.

   Templates use {{variable}} syntax. Variables are replaced with values
   from a context map. Unknown variables are left as-is.

   Layer 0: Interpolation engine
   Layer 1: Default templates (built-in fallbacks)
   Layer 2: Template resolution (rule → pack → default)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Interpolation

(defn interpolate
  "Replace {{variable}} placeholders in a template string with values
   from the bindings map. Keys can be strings or keywords.
   Unknown variables are replaced with empty string.

   Arguments:
   - template — String with {{variable}} placeholders
   - bindings — Map of variable names to values

   Returns:
   - Interpolated string"
  [template bindings]
  (str/replace template
               #"\{\{([^}]+)\}\}"
               (fn [[_ var-name]]
                 (let [kw (keyword var-name)
                       s  (str var-name)]
                   (str (or (get bindings kw)
                            (get bindings s)
                            ""))))))

;------------------------------------------------------------------------------ Layer 1
;; Default templates (loaded from EDN resource)

(def ^:private default-templates-resource
  "Classpath resource path for default prompt templates."
  "policy_pack/templates/defaults.edn")

(def ^:private loaded-defaults
  "Default templates loaded from classpath EDN resource. Delay for lazy loading."
  (delay
    (try
      (when-let [resource (io/resource default-templates-resource)]
        (edn/read-string (slurp resource)))
      (catch Exception _ {}))))

(defn- default-template
  "Get a default template by key. Returns empty string if not found."
  [k]
  (get @loaded-defaults k ""))

(def default-repair-prompt
  "Default prompt template for LLM-based semantic repair."
  (delay (default-template :repair-prompt)))

(def default-behavior-section
  "Default template for the behavior rules section."
  (delay (default-template :behavior-section)))

(def default-knowledge-section
  "Default template for the reference material section."
  (delay (default-template :knowledge-section)))

;------------------------------------------------------------------------------ Layer 2
;; Template resolution

(defn resolve-repair-template
  "Resolve the repair prompt template for a violation.

   Priority:
   1. Rule-level :rule/repair-prompt-template
   2. Pack-level :pack/prompt-templates :repair-prompt
   3. Built-in default

   Arguments:
   - rule — Rule map (may have :rule/repair-prompt-template)
   - pack — Pack map (may have :pack/prompt-templates)

   Returns:
   - Template string"
  [rule pack]
  (or (:rule/repair-prompt-template rule)
      (get-in pack [:pack/prompt-templates :repair-prompt])
      @default-repair-prompt))

(defn resolve-behavior-template
  "Resolve the behavior section template.

   Priority:
   1. Pack-level :pack/prompt-templates :behavior-section
   2. Built-in default

   Arguments:
   - pack — Pack map (may have :pack/prompt-templates)

   Returns:
   - Template string"
  [pack]
  (or (get-in pack [:pack/prompt-templates :behavior-section])
      @default-behavior-section))

(defn resolve-knowledge-template
  "Resolve the knowledge section template.

   Priority:
   1. Pack-level :pack/prompt-templates :knowledge-section
   2. Built-in default

   Arguments:
   - pack — Pack map (may have :pack/prompt-templates)

   Returns:
   - Template string"
  [pack]
  (or (get-in pack [:pack/prompt-templates :knowledge-section])
      @default-knowledge-section))

(defn render-repair-prompt
  "Render a complete repair prompt for a violation.

   Arguments:
   - violation — Violation map with :file, :line, :current, :rationale, :rule/id
   - rule      — Rule map with optional :rule/repair-prompt-template, :rule/knowledge-content
   - pack      — Pack map with optional :pack/prompt-templates

   Returns:
   - {:role :user :content <interpolated string>}"
  [violation rule pack]
  (let [template (resolve-repair-template rule pack)
        bindings {:file              (get violation :file "")
                  :line              (get violation :line "")
                  :current           (get violation :current "")
                  :rationale         (get violation :rationale "")
                  :rule-title        (get rule :rule/title (name (get violation :rule/id :unknown)))
                  :knowledge-content (get rule :rule/knowledge-content "")}]
    {:role    :user
     :content (interpolate template bindings)}))

(defn render-behavior-section
  "Render the behavior rules section for prompt injection.

   Arguments:
   - behaviors — Seq of behavior strings
   - pack      — Pack map with optional :pack/prompt-templates

   Returns:
   - Formatted string or nil if no behaviors"
  [behaviors pack]
  (when (seq behaviors)
    (let [template (resolve-behavior-template pack)
          numbered (->> behaviors
                        (map-indexed (fn [i b] (str (inc i) ". " b)))
                        (str/join "\n"))]
      (interpolate template {:behaviors numbered}))))

(defn render-knowledge-section
  "Render the reference material section for prompt injection.

   Arguments:
   - knowledge — Seq of {:rule/title :content} maps
   - pack      — Pack map with optional :pack/prompt-templates

   Returns:
   - Formatted string or nil if no knowledge"
  [knowledge pack]
  (when (seq knowledge)
    (let [template (resolve-knowledge-template pack)
          rendered (->> knowledge
                        (map (fn [{:keys [rule/title content]}]
                               (str "### " (or title "Standard") "\n\n" content)))
                        (str/join "\n\n"))]
      (interpolate template {:knowledge rendered}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Basic interpolation
  (interpolate "Hello {{name}}, welcome to {{place}}!"
               {:name "Chris" :place "miniforge"})
  ;; => "Hello Chris, welcome to miniforge!"

  ;; Render repair prompt with default template
  (render-repair-prompt
   {:file "src/core.clj" :line 42 :current "(eval x)" :rationale "eval is unsafe"}
   {:rule/title "No eval" :rule/knowledge-content "Avoid eval for security."}
   {})

  ;; Render with pack-provided template
  (render-repair-prompt
   {:file "main.tf" :line 10 :current "acl = \"public-read\"" :rationale "Public S3 bucket"}
   {:rule/title "No Public S3"}
   {:pack/prompt-templates
    {:repair-prompt "Fix this Terraform violation:\nFile: {{file}}:{{line}}\nIssue: {{rationale}}\nFix the `{{current}}` to be private."}})

  :leave-this-here)
