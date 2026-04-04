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

(ns ai.miniforge.policy-pack.interface.checking
  "Detection and artifact-checking helpers."
  (:require
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.detection :as detection]))

;------------------------------------------------------------------------------ Layer 0
;; Detection and checking

(def detect-violation detection/detect-violation)
(def check-rules detection/check-rules)
(def check-artifact core/check-artifact)
(def check-artifacts core/check-artifacts)
(def blocking-violations detection/blocking-violations)
(def approval-required-violations detection/approval-required-violations)
(def warning-violations detection/warning-violations)
(def violation->error detection/violation->error)
(def violation->warning detection/violation->warning)
