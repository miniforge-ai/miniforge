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
(ns ai.miniforge.agent-environment.interface
  "API surface class 1: the versioned agent-environment contract values.
   Every map is closed; a value naming another contract revision is refused."
  (:require
   [ai.miniforge.agent-environment.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every request and receipt names; any other is refused."
  schema/contract-version)

(def ^{:stratum 0} isolation-levels
  "Kernel boundaries a request may require; none is ever substituted."
  schema/isolation-levels)

(def ^{:stratum 0} Request "What a run asks for." schema/Request)

(def ^{:stratum 0} Environment "An acquired environment and its current lease." schema/Environment)

(def ^{:stratum 0} Lease "The fencing token every call after acquire presents." schema/Lease)

(def ^{:stratum 0} Receipt "One lifecycle call's record, refusals included." schema/Receipt)

(def ^{:stratum 0} Capabilities "What a provider declares it can supply." schema/Capabilities)

(def ^{:stratum 0} Command "One command to run inside an environment." schema/Command)
