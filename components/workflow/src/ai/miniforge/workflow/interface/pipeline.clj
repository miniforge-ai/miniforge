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

(ns ai.miniforge.workflow.interface.pipeline
  "Interceptor-pipeline workflow APIs."
  (:require
   [ai.miniforge.workflow.chain :as chain]
   [ai.miniforge.workflow.chain-loader :as chain-loader]
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline API

(def run-pipeline
  "Execute a workflow's interceptor pipeline end-to-end. Args:
   [workflow input] or [workflow input opts]. Acquires the execution
   environment, runs phases, validates, publishes, and always cleans up.
   Returns the final execution context map; throws a slingshot anomaly on
   governed-mode misconfiguration."
  runner/run-pipeline)

(def build-pipeline
  "Build the interceptor pipeline from a workflow config. Args: [workflow].
   Returns a vector of phase interceptors."
  runner/build-pipeline)

(def validate-pipeline
  "Validate a workflow's pipeline (phase + execution-machine checks). Args:
   [workflow]. Returns {:valid? boolean :errors [...] :warnings [...]}."
  runner/validate-pipeline)

(defn run-chain
  [chain-def chain-input opts]
  (chain/run-chain chain-def chain-input opts))

(defn load-chain
  [chain-id version]
  (chain-loader/load-chain chain-id version))

(defn list-chains
  []
  (chain-loader/list-chains))
