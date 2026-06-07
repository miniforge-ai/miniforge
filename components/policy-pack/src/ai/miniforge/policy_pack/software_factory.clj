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

(ns ai.miniforge.policy-pack.software-factory
  "Software-factory specific policy helpers built on the generic policy-pack SDK."
  (:require
   [ai.miniforge.policy-pack.external :as external]))

(def evaluate-external-pr
  "Evaluate an external PR diff against policy packs in read-only mode."
  external/evaluate-external-pr)

(def parse-pr-diff
  "Parse a unified diff into artifact-like inputs for external evaluation."
  external/parse-pr-diff)
