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
(ns ai.miniforge.cli.messages.locale
  "Locale-resolution primitives for the CLI message catalog. Split out of
   `ai.miniforge.cli.messages` (rule 210: the combined namespace measured 4
   real layers, max 3) — these are the independent building blocks the
   parent namespace's `active-locale` and `catalog` compose."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-locale "en-US")

(defn ^{:stratum 0} locale-resource
  [locale]
  (str "config/cli/messages/" locale ".edn"))

(defn ^{:stratum 0} lang->locale
  "Convert POSIX LANG (e.g. 'en_US.UTF-8') to BCP 47 tag (e.g. 'en-US')."
  [lang]
  (when-let [base (some-> lang (str/split #"\.") first not-empty)]
    (str/replace base "_" "-")))
