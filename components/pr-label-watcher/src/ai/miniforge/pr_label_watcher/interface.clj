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
(ns ai.miniforge.pr-label-watcher.interface
  "Public surface for the mechanical PR-label watcher.

   This brick is the M2 milestone of the labeled-PR-actions plan.
   It contains no LLM calls and no token spend — its job is to
   produce a deterministic match payload whenever a configured PR
   label fires on a merge. The meta-agent (M2b) and the rebase
   actuator (M3) consume these payloads downstream."
  (:require
   [ai.miniforge.pr-label-watcher.core :as core]))

;------------------------------------------------------------------------------ Layer 0

;; Registry loading
(def ^{:stratum 0} load-registry        core/load-registry)

(def ^{:stratum 0} matching-labels      core/matching-labels)

;; Ancestry + applicability
(def ^{:stratum 0} make-ancestor?-fn    core/make-ancestor?-fn)

(def ^{:stratum 0} workflow-applies?    core/workflow-applies?)

;; Top-level match — pure, all inputs explicit, no side effects.
(def ^{:stratum 0} match                core/match)

;; Payload construction (exposed so downstream consumers can synthesize
;; payloads in tests without going through the full match pipeline).
(def ^{:stratum 0} build-match-payload  core/build-match-payload)
