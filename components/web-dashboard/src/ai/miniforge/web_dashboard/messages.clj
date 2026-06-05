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

(ns ai.miniforge.web-dashboard.messages
  "Component-level message catalog for web-dashboard.
   Delegates to the shared messages component."
  (:require [ai.miniforge.messages.interface :as messages]))

(def ^:private catalog-path
  "config/web-dashboard/messages/en-US.edn")

(def ^:private catalog-section
  :web-dashboard/messages)

(def ^:private catalog
  (messages/load-catalog catalog-path catalog-section))

(def t
  "Look up a web-dashboard message by key, with optional param substitution."
  (messages/create-translator catalog-path catalog-section))

(defn all-as-json-map
  "Return the full catalog with keyword keys flattened to ns/name strings,
   suitable for direct JSON serialization into window.MINIFORGE_MESSAGES."
  []
  (into {}
        (map (fn [[k v]]
               [(subs (str k) 1) v]))
        (messages/all catalog)))
