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

(ns ai.miniforge.tui-views.prompts
  "Load and render LLM prompt templates from resources.

   Templates use {{variable-name}} placeholders, rendered at runtime
   with a variables map. This keeps prompts out of code and makes
   them easy to tune without recompilation.

   Layer 0 — no dependencies on other tui-views namespaces."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Template loading

(def ^:private prompt-templates
  "Lazily loaded prompt templates from resources/config/tui/prompts.edn."
  (delay
    (if-let [resource (io/resource "config/tui/prompts.edn")]
      (edn/read-string (slurp resource))
      {})))

(defn get-template
  "Look up a prompt template by key. Returns the raw template string or nil."
  [template-key]
  (get @prompt-templates template-key))

;------------------------------------------------------------------------------ Layer 0a
;; Template rendering

(defn render
  "Render a prompt template with variable substitution.
   vars is a map of {\"variable-name\" \"value\"} or {:variable-name \"value\"}.
   Replaces all {{variable-name}} placeholders."
  [template-key vars]
  (let [tmpl (or (get-template template-key)
                 (throw (ex-info (str "Unknown prompt template: " template-key)
                                 {:template-key template-key})))]
    (reduce-kv (fn [s k v]
                 (str/replace s
                              (str "{{" (name k) "}}")
                              (str v)))
               tmpl
               vars)))
