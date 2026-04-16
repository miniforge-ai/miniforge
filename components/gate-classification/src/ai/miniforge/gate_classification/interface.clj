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

(ns ai.miniforge.gate-classification.interface
  "Public API for the classification gate."
  (:require [ai.miniforge.gate-classification.core :as core]))

(def default-config
  "Default gate configuration with sensible thresholds."
  {:confidence-threshold    0.5
   :doc-status-threshold    0.7
   :true-positive-threshold 0.8
   :max-error-severity      :error})

(defn create-classification-gate
  "Create a ClassificationGate instance.
   Accepts optional config overrides merged with defaults."
  ([] (create-classification-gate {}))
  ([config-overrides]
   (core/->ClassificationGate
    :classification-gate
    (merge default-config config-overrides))))
