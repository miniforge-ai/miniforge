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
  "Resource-backed message catalog for shared CLI user-facing copy. Locale
   resolution primitives live in `ai.miniforge.cli.messages.locale` and
   template-value rendering lives in `ai.miniforge.cli.messages.rendering`
   (rule 210 split); this namespace composes them into the public
   `catalog`/`t` API."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages.locale :as locale]
   [ai.miniforge.cli.messages.rendering :as rendering]
   [ai.miniforge.cli.resource-config :as resource-config]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} active-locale
  []
  (or (some-> (System/getenv "MINIFORGE_LOCALE") str/trim not-empty)
      (locale/lang->locale (System/getenv "LANG"))
      locale/default-locale))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} catalog
  "Load the active message catalog, falling back to English."
  ([] (catalog (active-locale)))
  ([locale-tag]
   (let [catalog-data (resource-config/merged-resource-config (locale/locale-resource locale-tag)
                                                               :cli/messages
                                                               {})]
     (if (or (= locale-tag locale/default-locale) (seq catalog-data))
       catalog-data
       (catalog locale/default-locale)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} t
  "Return a rendered message value for `message-key`.

   Supports strings, vectors, and maps loaded from EDN resources."
  ([message-key]
   (t message-key {}))
  ([message-key params]
   (if-let [value (get (catalog) message-key)]
     (rendering/render-value value params)
     (response/throw-anomaly! :anomalies/not-found
                             "Missing CLI message key"
                             {:message-key message-key
                              :locale (active-locale)}))))
