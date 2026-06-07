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

(ns ai.miniforge.policy-pack.interface.intent
  "Semantic intent validation — declared intent vs actual resource changes."
  (:require
   [ai.miniforge.policy-pack.intent :as intent]))

(def intent-types
  "Set of valid intent keywords
   `#{:import :create :update :destroy :refactor :migrate}`."
  intent/intent-types)

(def infer-intent
  "Infer the intent keyword from resource change counts.
   (infer-intent {:creates :updates :destroys}) returns one of
   :refactor/:create/:update/:destroy/:migrate, or :mixed when no clear pattern."
  intent/infer-intent)

(def intent-matches?
  "Validate that a declared intent matches actual resource change counts.
   (intent-matches? declared counts) returns {:passed? true} when consistent,
   else {:passed? false :violations [...]}. Unknown intents pass by default."
  intent/intent-matches?)

(def semantic-intent-check
  "Full semantic intent check (N4 §4).
   (semantic-intent-check declared-intent counts) returns
   {:passed? bool :violations [...] :inferred-intent keyword :metadata {...}}."
  intent/semantic-intent-check)

(def parse-terraform-plan-counts
  "Parse terraform plan output into resource change counts.
   (parse-terraform-plan-counts plan-output) returns
   {:creates int :updates int :destroys int}."
  intent/parse-terraform-plan-counts)

(def parse-k8s-diff-counts
  "Parse kubectl diff output into resource change counts.
   (parse-k8s-diff-counts diff-output) returns
   {:creates int :updates int :destroys int}."
  intent/parse-k8s-diff-counts)
