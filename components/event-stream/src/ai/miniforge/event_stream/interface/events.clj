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

(ns ai.miniforge.event-stream.interface.events
  "Workflow, agent, gate, task, listener, control, chain, and control-plane event constructors."
  (:require
   [ai.miniforge.event-stream.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Event constructors

(def workflow-started core/workflow-started)
(def phase-started core/phase-started)
(def phase-completed core/phase-completed)
(def agent-chunk core/agent-chunk)
(def agent-status core/agent-status)
(def agent-tool-call core/agent-tool-call)
(def workflow-completed core/workflow-completed)
(def workflow-failed core/workflow-failed)
(def llm-request core/llm-request)
(def llm-response core/llm-response)
(def agent-started core/agent-started)
(def agent-completed core/agent-completed)
(def agent-failed core/agent-failed)
(def gate-started core/gate-started)
(def gate-passed core/gate-passed)
(def gate-failed core/gate-failed)
(def tool-invoked core/tool-invoked)
(def tool-completed core/tool-completed)
(def milestone-reached core/milestone-reached)
(def milestone-started core/milestone-started)
(def milestone-completed core/milestone-completed)
(def milestone-failed core/milestone-failed)
(def task-state-changed core/task-state-changed)
(def task-frontier-entered core/task-frontier-entered)
(def task-skip-propagated core/task-skip-propagated)
(def inter-agent-message-sent core/inter-agent-message-sent)
(def inter-agent-message-received core/inter-agent-message-received)
(def listener-attached core/listener-attached)
(def listener-detached core/listener-detached)
(def annotation-created core/annotation-created)
(def control-action-requested core/control-action-requested)
(def control-action-executed core/control-action-executed)
(def chain-started core/chain-started)
(def chain-step-started core/chain-step-started)
(def chain-step-completed core/chain-step-completed)
(def chain-step-failed core/chain-step-failed)
(def chain-completed core/chain-completed)
(def chain-failed core/chain-failed)

;------------------------------------------------------------------------------ Layer 1
;; OCI container event constructors

(def container-started core/container-started)
(def container-completed core/container-completed)

;------------------------------------------------------------------------------ Layer 1
;; Tool supervision event constructors

(def tool-use-evaluated core/tool-use-evaluated)

;------------------------------------------------------------------------------ Layer 1
;; Control plane event constructors

(def cp-agent-registered core/cp-agent-registered)
(def cp-agent-heartbeat core/cp-agent-heartbeat)
(def cp-agent-state-changed core/cp-agent-state-changed)
(def cp-decision-created core/cp-decision-created)
(def cp-decision-resolved core/cp-decision-resolved)
(def intervention-requested core/intervention-requested)
(def intervention-state-changed core/intervention-state-changed)

;------------------------------------------------------------------------------ Layer 2
;; PR scoring event constructors (N5-delta-2 §4.1)

(def pr-created core/pr-created)
(def pr-scored core/pr-scored)

;------------------------------------------------------------------------------ Layer 2
;; Reliability metric event constructors (N3 §3.17)

(def sli-computed core/sli-computed)
(def slo-breach core/slo-breach)
(def error-budget-update core/error-budget-update)
(def dependency-health-updated core/dependency-health-updated)
(def dependency-recovered core/dependency-recovered)
(def degradation-mode-changed core/degradation-mode-changed)
(def safe-mode-entered core/safe-mode-entered)
(def safe-mode-exited core/safe-mode-exited)

;------------------------------------------------------------------------------ Layer 2
;; Meta-loop event constructors

(def meta-loop-cycle-completed core/meta-loop-cycle-completed)
(def meta-loop-cycle-failed core/meta-loop-cycle-failed)

;------------------------------------------------------------------------------ Layer 3
;; Observer / knowledge failure event constructors

(def observer-signal-failed core/observer-signal-failed)
(def knowledge-synthesis-failed core/knowledge-synthesis-failed)
(def knowledge-promotion-failed core/knowledge-promotion-failed)
