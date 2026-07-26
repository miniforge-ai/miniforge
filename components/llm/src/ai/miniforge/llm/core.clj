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
(ns ai.miniforge.llm.core
  "LLM client implementation using CLI backends.

   Note: The protocol and implementation have been moved to:
   - ai.miniforge.llm.interface.protocols.llm-client (LLMClient protocol)
   - ai.miniforge.llm.protocols.impl.llm-client (implementation functions)
   - ai.miniforge.llm.protocols.records.llm-client (CLIClient record)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.llm.interface.protocols.llm-client :as p]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.records.llm-client :as records]))

;------------------------------------------------------------------------------ Layer 0

;; Re-export protocol for backward compatibility
(def ^{:stratum 0} LLMClient p/LLMClient)

;; Re-export protocol methods
(def ^{:stratum 0} complete* p/complete*)

(def ^{:stratum 0} get-config p/get-config)

;; Re-export implementation functions
(def ^{:stratum 0} backends impl/backends)

(def ^{:stratum 0} build-messages-prompt impl/build-messages-prompt)

(def ^{:stratum 0} parse-cli-output impl/parse-cli-output)

(def ^{:stratum 0} default-exec-fn impl/default-exec-fn)

(def ^{:stratum 0} mock-exec-fn impl/mock-exec-fn)

(def ^{:stratum 0} mock-exec-fn-multi impl/mock-exec-fn-multi)

;; Re-export factory functions
(def ^{:stratum 0} create-client records/create-client)
