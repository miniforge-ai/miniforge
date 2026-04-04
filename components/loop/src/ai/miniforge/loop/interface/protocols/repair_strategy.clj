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

(ns ai.miniforge.loop.interface.protocols.repair-strategy
  "Public protocol for artifact repair strategies.

   This is an extensibility point - users can implement custom repair
   strategies by implementing this protocol.")

(defprotocol RepairStrategy
  "Protocol for artifact repair strategies.
   Strategies attempt to fix validation errors in artifacts."
  (can-repair? [this errors context]
    "Check if this strategy can handle the given errors.
     Returns true if the strategy should be attempted.")
  (repair [this artifact errors context]
    "Attempt to repair the artifact given the errors.
     Returns:
     {:success? boolean
      :artifact artifact-map (if success)
      :errors [error...] (if failure)
      :strategy keyword
      :tokens-used int (optional)
      :duration-ms int (optional)}
     The context map provides access to agent, logger, and config."))
