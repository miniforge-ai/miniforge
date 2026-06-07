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

(ns ai.miniforge.policy-pack.interface.prompt-template
  "Pack-bundled prompt template interpolation and rendering."
  (:require
   [ai.miniforge.policy-pack.prompt-template :as pt]))

(def interpolate
  "Replace {{variable}} placeholders in a template with values from a bindings
   map (keys may be strings or keywords; unknown variables become \"\").
   (interpolate template bindings) returns the interpolated string."
  pt/interpolate)

(def render-repair-prompt
  "Render a complete repair prompt for a violation.
   (render-repair-prompt violation rule pack) resolves the repair template
   (rule → pack → default), interpolates it, and returns
   {:role :user :content <string>}."
  pt/render-repair-prompt)

(def render-behavior-section
  "Render the numbered behavior-rules section for prompt injection.
   (render-behavior-section behaviors pack) returns the formatted string, or
   nil when behaviors is empty."
  pt/render-behavior-section)

(def render-knowledge-section
  "Render the reference-material section for prompt injection.
   (render-knowledge-section knowledge pack), where knowledge is a seq of
   {:rule/title :content} maps, returns the formatted string, or nil when
   empty."
  pt/render-knowledge-section)

(def resolve-repair-template
  "Resolve the repair prompt template string for a violation, preferring the
   rule's :rule/repair-prompt-template, then the pack's :repair-prompt, then the
   built-in default. (resolve-repair-template rule pack) returns the template
   string."
  pt/resolve-repair-template)

(def resolve-behavior-template
  "Resolve the behavior-section template string, preferring the pack's
   :behavior-section, else the built-in default. (resolve-behavior-template
   pack) returns the template string."
  pt/resolve-behavior-template)

(def resolve-knowledge-template
  "Resolve the knowledge-section template string, preferring the pack's
   :knowledge-section, else the built-in default. (resolve-knowledge-template
   pack) returns the template string."
  pt/resolve-knowledge-template)
