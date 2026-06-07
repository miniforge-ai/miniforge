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

(ns ai.miniforge.agent.interface.classification
  "Task-classification helpers for intelligent model selection."
  (:require
   [ai.miniforge.agent.task-classifier :as classifier]))

;------------------------------------------------------------------------------ Layer 0
;; Task classification

(def classify-task
  "Classify a task to pick an optimal model type.
   Arg: task map with optional :phase :agent-type :description :title and
   sizing/privacy/cost hints. Returns a map
   {:type keyword :confidence double :reason string
    :alternative-types [keyword] :all-reasons [string]}; never nil
   (defaults to :execution-focused at 0.5 confidence)."
  classifier/classify-task)

(def get-task-characteristics
  "Extract the features used during classification, for debugging/transparency.
   Arg: task map. Returns a map
   {:phase :agent-type :keywords #{kw} :context-size int
    :privacy-required truthy-or-nil :cost-constrained truthy-or-nil}."
  classifier/get-task-characteristics)
