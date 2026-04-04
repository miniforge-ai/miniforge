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

(ns ai.miniforge.llm.model-registry
  "Model registry with capability profiles for intelligent model selection.
   Layer 0: Model capability definitions (resource-backed data)
   Layer 1: Query functions (by capability, use-case, task-type)
   Layer 2: Recommendation logic"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Model capability definitions

(def capability-levels
  "Capability levels from lowest to highest"
  [:poor :fair :good :excellent :exceptional])

(def speed-levels
  "Speed capability levels from slowest to fastest"
  [:slow :moderate :balanced :fast :very-fast])

(defn- load-model-catalog
  "Load the model catalog from EDN resources."
  []
  (if-let [resource (io/resource "llm/model-catalog.edn")]
    (edn/read-string (slurp resource))
    (throw (ex-info "Missing llm/model-catalog.edn resource" {}))))

(def ^:private model-catalog
  "Resource-backed model catalog and task-type recommendations."
  (delay (load-model-catalog)))

(def model-registry
  "Comprehensive registry of all supported models with their capabilities."
  (:models @model-catalog))

;------------------------------------------------------------------------------ Layer 1
;; Query functions

(defn get-model
  "Get a model's full profile by keyword."
  [model-key]
  (get model-registry model-key))

(defn get-models-by-capability
  "Get models meeting or exceeding a capability level.
   Example: (get-models-by-capability :reasoning :excellent)
   Returns: [:opus-4.6 :sonnet-4.6 :gpt-5.3-codex ...]"
  [capability min-level]
  (let [level-idx (.indexOf capability-levels min-level)]
    (when (>= level-idx 0)
      (->> model-registry
           (filter (fn [[_k v]]
                     (let [model-level (get-in v [:capabilities capability])
                           model-idx (.indexOf capability-levels model-level)]
                       (and (>= model-idx 0)
                            (>= model-idx level-idx)))))
           (map first)
           (into [])))))

(defn get-models-by-speed
  "Get models meeting or exceeding a speed level.
   Example: (get-models-by-speed :fast)
   Returns: [:haiku-4.5 :gemini-2.5-flash-lite ...]"
  [min-speed]
  (let [level-idx (.indexOf speed-levels min-speed)]
    (when (>= level-idx 0)
      (->> model-registry
           (filter (fn [[_k v]]
                     (let [model-speed (get-in v [:capabilities :speed])
                           model-idx (.indexOf speed-levels model-speed)]
                       (and (>= model-idx 0)
                            (>= model-idx level-idx)))))
           (map first)
           (into [])))))

(defn get-models-by-use-case
  "Get models that support a specific use-case.
   Example: (get-models-by-use-case :code-implementation)
   Returns: [:sonnet-4.6 :gpt-5.2-codex ...]"
  [use-case]
  (->> model-registry
       (filter (fn [[_k v]]
                 (contains? (:use-cases v) use-case)))
       (map first)
       (into [])))

(defn get-models-by-provider
  "Get all models from a specific provider."
  [provider]
  (->> model-registry
       (filter (fn [[_k v]] (= (:provider v) provider)))
       (map first)
       (into [])))

(defn get-local-models
  "Get all local models (for privacy-sensitive tasks)."
  []
  (->> model-registry
       (filter (fn [[_k v]] (get-in v [:capabilities :local])))
       (map first)
       (into [])))

(defn supports-large-context?
  "Check if model supports contexts larger than threshold (default 200k)."
  ([model-key] (supports-large-context? model-key 200000))
  ([model-key threshold]
   (when-let [model (get-model model-key)]
     (>= (get-in model [:capabilities :context-window]) threshold))))

;------------------------------------------------------------------------------ Layer 2
;; Recommendation logic

(def task-type-recommendations
  "Recommended models for each task type, organized by tier."
  (:task-type-recommendations @model-catalog))

(defn recommend-models-for-task-type
  "Get recommended models for a task type.
   Returns map with :tier-1, :tier-2, :tier-3-local, and :rationale."
  [task-type]
  (get task-type-recommendations task-type))

(defn get-primary-recommendation
  "Get the primary recommended model for a task type.
   Returns the first model from tier-1."
  [task-type]
  (first (get-in task-type-recommendations [task-type :tier-1])))
