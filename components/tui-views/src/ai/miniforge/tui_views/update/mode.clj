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

(ns ai.miniforge.tui-views.update.mode
  "Mode switching and command buffer manipulation.

   Pure functions for switching between normal/command/search modes.
   Layer 3."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 3
;; Mode switching

(defn enter-command-mode [model]
  (assoc model :mode :command :command-buf ":"))

(defn enter-search-mode [model]
  (assoc model :mode :search :command-buf "/" :search-results []))

(defn exit-mode [model]
  (assoc model :mode :normal :command-buf "" :search-results []
         :filtered-indices nil))

(defn command-append [model ch]
  (update model :command-buf str ch))

(defn command-backspace [model]
  (let [buf (:command-buf model)]
    (if (> (count buf) 1)
      (assoc model :command-buf (subs buf 0 (dec (count buf))))
      (exit-mode model))))

;------------------------------------------------------------------------------ Layer 4
;; Search filtering

(defn compute-search-results
  "Filter workflows matching search query (case-insensitive substring match).
   Sets :filtered-indices on the model."
  [model]
  (let [query (subs (:command-buf model) 1) ; strip leading "/"
        workflows (:workflows model)]
    (if (str/blank? query)
      (assoc model :filtered-indices nil)
      (let [matches (set (keep-indexed
                           (fn [idx wf]
                             (when (str/includes?
                                     (str/lower-case (or (:name wf) ""))
                                     (str/lower-case query))
                               idx))
                           workflows))]
        (assoc model :filtered-indices matches :selected-idx 0)))))
