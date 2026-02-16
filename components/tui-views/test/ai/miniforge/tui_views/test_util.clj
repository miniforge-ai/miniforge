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

(ns ai.miniforge.tui-views.test-util
  "Test utilities for tui-views tests.

   Provides common helpers to reduce test duplication."
  (:require
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.update :as update]))

;; Setup helpers

(defn fresh-model
  "Create a fresh model for testing."
  []
  (model/init-model))

(defn with-workflows
  "Add workflows to model for testing.
   workflows: vector of {:workflow-id :name} maps"
  [model workflows]
  (reduce (fn [m wf]
            (update/update-model m [:msg/workflow-added wf]))
          model
          workflows))

(defn apply-updates
  "Apply multiple update messages to a model.
   updates: vector of [msg-type payload] vectors"
  [model updates]
  (reduce update/update-model model updates))

;; Assertion helpers

(defn view-is?
  "Check if model's current view matches expected."
  [model expected-view]
  (= expected-view (:view model)))

(defn selected-idx-is?
  "Check if model's selected index matches expected."
  [model expected-idx]
  (= expected-idx (:selected-idx model)))

(defn workflow-count-is?
  "Check if model has expected number of workflows."
  [model expected-count]
  (= expected-count (count (:workflows model))))

(defn workflow-has-status?
  "Check if workflow at index has expected status."
  [model idx expected-status]
  (= expected-status (get-in model [:workflows idx :status])))

(defn workflow-has-phase?
  "Check if workflow at index has expected phase."
  [model idx expected-phase]
  (= expected-phase (get-in model [:workflows idx :phase])))

(defn mode-is?
  "Check if model's mode matches expected."
  [model expected-mode]
  (= expected-mode (:mode model)))

;; Selection assertion helpers

(defn selection-count-is?
  "Check if model has expected number of selected items."
  [model expected-count]
  (= expected-count (count (:selected-ids model))))

(defn has-selection?
  "Check if model has a specific ID selected."
  [model id]
  (contains? (:selected-ids model) id))

(defn visual-mode?
  "Check if visual mode is active."
  [model]
  (some? (:visual-anchor model)))

(defn confirm-active?
  "Check if a confirmation prompt is active."
  [model]
  (some? (:confirm model)))
