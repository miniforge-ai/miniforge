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

(ns ai.miniforge.orchestrator.messages
  "Component-level message catalog for orchestrator.

   Loads localized strings from EDN resources on the classpath.
   Falls back to message key name when a key is missing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private resource-path "config/orchestrator/messages/en-US.edn")
(def ^:private section-key :orchestrator/messages)

(defn- load-catalog []
  (when-let [res (io/resource resource-path)]
    (get (edn/read-string (slurp res)) section-key {})))

(def ^:private catalog (delay (load-catalog)))

(defn t
  "Look up a message by key, substituting params into {placeholder} tokens."
  ([k] (t k {}))
  ([k params]
   (let [template (get @catalog k (name k))]
     (reduce-kv (fn [s pk pv]
                  (str/replace s (str "{" (name pk) "}") (str pv)))
                template
                params))))
