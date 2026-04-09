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

(def interpolate pt/interpolate)
(def render-repair-prompt pt/render-repair-prompt)
(def render-behavior-section pt/render-behavior-section)
(def render-knowledge-section pt/render-knowledge-section)
(def resolve-repair-template pt/resolve-repair-template)
(def resolve-behavior-template pt/resolve-behavior-template)
(def resolve-knowledge-template pt/resolve-knowledge-template)
