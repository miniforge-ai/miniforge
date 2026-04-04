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

(ns ai.miniforge.cli.messages
  "Resource-backed message catalog for shared CLI user-facing copy."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.resource-config :as resource-config]))

;------------------------------------------------------------------------------ Layer 0
;; Catalog loading

(def default-locale "en-US")

(defn- locale-resource
  [locale]
  (str "config/cli/messages/" locale ".edn"))

(defn- lang->locale
  "Convert POSIX LANG (e.g. 'en_US.UTF-8') to BCP 47 tag (e.g. 'en-US')."
  [lang]
  (when-let [base (some-> lang (str/split #"\.") first not-empty)]
    (str/replace base "_" "-")))

(defn active-locale
  []
  (or (some-> (System/getenv "MINIFORGE_LOCALE") str/trim not-empty)
      (lang->locale (System/getenv "LANG"))
      default-locale))

(defn catalog
  "Load the active message catalog, falling back to English."
  ([] (catalog (active-locale)))
  ([locale]
   (let [catalog-data (resource-config/merged-resource-config (locale-resource locale)
                                                              :cli/messages
                                                              {})]
     (if (or (= locale default-locale) (seq catalog-data))
       catalog-data
       (catalog default-locale)))))

;------------------------------------------------------------------------------ Layer 1
;; Template rendering

(defn- render-string
  [template params]
  (reduce-kv (fn [rendered key value]
               (str/replace rendered
                            (str "{" (name key) "}")
                            (str value)))
             template
             params))

(defn- render-value
  [value params]
  (cond
    (string? value) (render-string value params)
    (vector? value) (mapv #(render-value % params) value)
    (map? value) (into {} (map (fn [[key entry]]
                                 [key (render-value entry params)]))
                     value)
    :else value))

(defn t
  "Return a rendered message value for `message-key`.

   Supports strings, vectors, and maps loaded from EDN resources."
  ([message-key]
   (t message-key {}))
  ([message-key params]
   (if-let [value (get (catalog) message-key)]
     (render-value value params)
     (throw (ex-info "Missing CLI message key"
                     {:message-key message-key
                      :locale (active-locale)})))))
