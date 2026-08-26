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
(ns ai.miniforge.redaction.interface
  "Redaction of never-emitted values per N3 §8."
  (:require
   [ai.miniforge.redaction.core :as core]
   [ai.miniforge.redaction.policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} redact
  "Redact X — any nested data structure — replacing values excluded by
   N3 §8.1 with the `[REDACTED]` marker (N3 §8.2).

   Callers redact at construction. A redacting sink is not conformant:
   by then the event is already sequenced and durable (N3 §8.1)."
  [x]
  (core/redact x))

(defn ^{:stratum 0} clean?
  "True when X carries no value excluded by N3 §8.1."
  [x]
  (core/clean? x))

(defn ^{:stratum 0} marker
  "The redaction marker substituted for an excluded value."
  []
  (policy/marker))
