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

(ns ai.miniforge.messages.core
  "Shared message catalog loader.

   Provides a single implementation of EDN-based message loading
   with placeholder substitution. All components delegate here
   instead of maintaining their own copy."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn load-catalog
  "Load a message catalog from an EDN resource on the classpath.

   Arguments:
     resource-path - Classpath-relative path (e.g. \"config/workflow/messages/en-US.edn\")
     section-key   - Key to extract from the EDN root map (e.g. :workflow/messages)

   Returns: A delay wrapping the message map."
  [resource-path section-key]
  (delay
    (when-let [res (io/resource resource-path)]
      (get (edn/read-string (slurp res)) section-key {}))))

(defn t
  "Look up a message by key, substituting params into {placeholder} tokens.

   Arguments:
     catalog - A delay (from load-catalog) containing the message map
     k       - Message key
     params  - Optional map of placeholder replacements

   Returns: The resolved message string, or the key name as fallback."
  ([catalog k] (t catalog k {}))
  ([catalog k params]
   (let [template (get @catalog k (name k))]
     (if (string? template)
       (reduce-kv (fn [s pk pv]
                    (str/replace s (str "{" (name pk) "}") (str pv)))
                  template
                  params)
       template))))

(defn create-translator
  "Create a translator function for a specific component's message catalog.

   Arguments:
     resource-path - Classpath-relative path to the EDN file
     section-key   - Key to extract from the EDN root map

   Returns: A function (fn t [k] ...) / (fn t [k params] ...) that
            looks up messages from the loaded catalog."
  [resource-path section-key]
  (let [catalog (load-catalog resource-path section-key)]
    (fn
      ([k] (t catalog k {}))
      ([k params] (t catalog k params)))))
