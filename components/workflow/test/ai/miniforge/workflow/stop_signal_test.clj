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
(ns ai.miniforge.workflow.stop-signal-test
  "Recovery scenario C4: a cancel reaches work in flight. The work is a
   real subprocess the waiting thread kills when interrupted, the contract
   both LLM paths keep for the agent CLI."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-loop :as dag-loop]
   [ai.miniforge.workflow.execution :as exec]
   [ai.miniforge.workflow.execution-step :as execution-step]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.runner-checkpoint :as runner-checkpoint]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-iteration :as runner-iteration]
   [ai.miniforge.workflow.stop-signal :as stop-signal]
   [clojure.test :refer [deftest is testing]]))

