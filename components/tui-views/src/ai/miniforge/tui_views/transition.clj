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

(ns ai.miniforge.tui-views.transition
  "State transition helpers for the Elm model.

   Provides constructors for common model mutations — mode transitions,
   selection resets, flash messages, and view switches — so they are
   defined in one place and callers read as a DSL.

   Layer 0 — no dependencies on other tui-views namespaces.")

;------------------------------------------------------------------------------ Layer 0
;; Flash messages

(defn flash
  "Set a flash message on the model."
  [model msg]
  (assoc model :flash-message msg))

;------------------------------------------------------------------------------ Layer 0b
;; Selection and filter defaults

(def selection-defaults
  "Default values for selection/scroll state."
  {:selected-idx 0 :scroll-offset 0 :selected-ids #{} :visual-anchor nil})

(def filter-defaults
  "Default values for search/filter state."
  {:filtered-indices nil :search-matches [] :search-match-idx nil})

(defn reset-selection
  "Reset selection and scroll state. Optionally clear filter state too."
  ([model]
   (merge model selection-defaults))
  ([model {:keys [clear-filter?]}]
   (cond-> (merge model selection-defaults)
     clear-filter? (merge filter-defaults))))

;------------------------------------------------------------------------------ Layer 1
;; Mode transitions

(defn enter-command
  "Enter command mode with ':' prompt."
  [model]
  (assoc model :mode :command :command-buf ":"))

(defn enter-search
  "Enter search mode with '/' prompt."
  [model]
  (assoc model :mode :search :command-buf "/" :search-results []))

(defn enter-filter
  "Enter filter mode with '>' prompt."
  [model]
  (assoc model :mode :filter :command-buf ">"))

(defn enter-normal
  "Return to normal mode, clearing command buffer and search results."
  [model]
  (assoc model :mode :normal :command-buf "" :search-results []))

(defn enter-normal-clear-all
  "Return to normal mode and clear all filter/search/selection state."
  [model]
  (merge model {:mode :normal :command-buf ""
                :search-results [] :filtered-indices nil
                :search-matches [] :search-match-idx nil
                :selected-idx 0 :active-filter nil}))

;------------------------------------------------------------------------------ Layer 2
;; View switches

(defn switch-view
  "Switch to a top-level view, resetting navigation state."
  [model view-key]
  (-> model
      (merge selection-defaults filter-defaults)
      (assoc :view view-key)
      (assoc-in [:detail :workflow-id] nil)))
