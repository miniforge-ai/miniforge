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
(ns ai.miniforge.agent-worker.interface
  "API surface class 1: the versioned agent-worker contract values. Every
   worker call is bound to run, task, environment UID, lease generation,
   spend grant, config digest and attempt. Unknown usage is nil, never zero."
  (:require
   [ai.miniforge.agent-worker.schema :as schema]
   [ai.miniforge.agent-worker.session :as session]
   [ai.miniforge.agent-worker.validation :as v]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} contract-version
  "The revision every binding names; any other is refused."
  schema/contract-version)

(def ^{:stratum 0} Binding "What every worker call is bound to." schema/Binding)

(def ^{:stratum 0} Config "The explicitly selected worker and its options." schema/Config)

(def ^{:stratum 0} Submission "One attempt: binding, config and prompt." schema/Submission)

(def ^{:stratum 0} Session "A started attempt and its native references." schema/Session)

(def ^{:stratum 0} Status "What inspection reports." schema/Status)

(def ^{:stratum 0} Usage "Reported usage; unknown quantities are nil." schema/Usage)

(def ^{:stratum 0} Capabilities "What a worker declares it can do." schema/Capabilities)

(defn ^{:stratum 0} bind
  "The binding for attempt `attempt` of `config` in the leased environment
   under spend grant `grant`."
  [acting lease grant config attempt]
  (or (v/check v/Acting acting v/Lease lease v/Grant grant schema/Config config pos-int? attempt)
      (session/bind acting lease grant config attempt)))
