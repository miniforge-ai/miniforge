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
(ns ai.miniforge.tenancy.ids
  "Stable identity ids derived from a configured name.

   Stability is the requirement, not a nicety: a tenant id that changed
   per process would make yesterday's records look like they belong to
   someone else, and ownership that drifts is worse than no ownership
   because it looks authoritative while being wrong."
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} identity-namespace
  "Fixed namespace for name-derived identity ids."
  (UUID/fromString "b4f1a2c6-8d3e-4a71-9c25-6f0e8ab41d33"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stable-id
  "Same namespace + kind + name always yields the same id."
  ^UUID [kind value]
  (UUID/nameUUIDFromBytes
   (.getBytes (str identity-namespace ":" kind ":" value) "UTF-8")))
