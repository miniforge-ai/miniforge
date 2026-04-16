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

(ns ai.miniforge.agent.interface
  "Canonical public boundary for the agent component.
   Public API groups are decomposed into ai.miniforge.agent.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.agent.interface.classification :as classification]
   [ai.miniforge.agent.interface.llm :as llm]
   [ai.miniforge.agent.interface.memory :as memory]
   [ai.miniforge.agent.interface.messaging :as messaging]
   [ai.miniforge.agent.interface.meta :as meta]
   [ai.miniforge.agent.interface.protocols :as protocols]
   [ai.miniforge.agent.interface.runtime :as runtime]
   [ai.miniforge.agent.interface.specialized :as specialized]
   [ai.miniforge.agent.tool-supervisor :as tool-supervisor]))

;------------------------------------------------------------------------------ Layer 0
;; Protocols and configuration

(def Agent protocols/Agent)
(def AgentLifecycle protocols/AgentLifecycle)
(def AgentExecutor protocols/AgentExecutor)
(def LLMBackend protocols/LLMBackend)
(def Memory protocols/Memory)
(def MemoryStore protocols/MemoryStore)
(def InterAgentMessaging protocols/InterAgentMessaging)
(def MessageRouter protocols/MessageRouter)
(def MessageValidator protocols/MessageValidator)
(def default-role-configs protocols/default-role-configs)
(def role-capabilities protocols/role-capabilities)
(def role-system-prompts protocols/role-system-prompts)

;------------------------------------------------------------------------------ Layer 1
;; Runtime and memory API

(def create-agent runtime/create-agent)
(def create-agent-map runtime/create-agent-map)
(def create-executor runtime/create-executor)
(def execute runtime/execute)
(def invoke runtime/invoke)
(def validate runtime/validate)
(def repair runtime/repair)
(def init runtime/init)
(def agent-status runtime/agent-status)
(def shutdown runtime/shutdown)

(def create-memory memory/create-memory)
(def add-to-memory memory/add-to-memory)
(def add-system-message memory/add-system-message)
(def add-user-message memory/add-user-message)
(def add-assistant-message memory/add-assistant-message)
(def get-messages memory/get-messages)
(def get-memory-window memory/get-memory-window)
(def clear-memory memory/clear-memory)
(def memory-metadata memory/memory-metadata)
(def create-memory-store memory/create-memory-store)
(def get-memory memory/get-memory)
(def save-memory memory/save-memory)
(def delete-memory memory/delete-memory)
(def list-memories memory/list-memories)

;------------------------------------------------------------------------------ Layer 2
;; Messaging, utilities, specialized agents, and meta-operations

(def create-message-router messaging/create-message-router)
(def create-agent-messaging messaging/create-agent-messaging)
(def send-message messaging/send-message)
(def receive-messages messaging/receive-messages)
(def respond-to-message messaging/respond-to-message)
(def send-clarification-request messaging/send-clarification-request)
(def send-concern messaging/send-concern)
(def send-suggestion messaging/send-suggestion)
(def get-clarification-requests messaging/get-clarification-requests)
(def get-concerns messaging/get-concerns)
(def get-suggestions messaging/get-suggestions)
(def route-message messaging/route-message)
(def get-messages-for-agent messaging/get-messages-for-agent)
(def get-messages-by-workflow messaging/get-messages-by-workflow)
(def clear-workflow-messages messaging/clear-workflow-messages)
(def validate-message messaging/validate-message)
(def Message messaging/Message)
(def MessageType messaging/MessageType)

(def estimate-tokens llm/estimate-tokens)
(def estimate-cost llm/estimate-cost)
(def create-mock-llm llm/create-mock-llm)

(def create-base-agent specialized/create-base-agent)
(def make-validator specialized/make-validator)
(def cycle-agent specialized/cycle-agent)
(def create-planner specialized/create-planner)
(def create-implementer specialized/create-implementer)
(def create-tester specialized/create-tester)
(def create-reviewer specialized/create-reviewer)
(def create-releaser specialized/create-releaser)
(def Plan specialized/Plan)
(def PlanTask specialized/PlanTask)
(def CodeArtifact specialized/CodeArtifact)
(def CodeFile specialized/CodeFile)
(def TestArtifact specialized/TestArtifact)
(def TestFile specialized/TestFile)
(def Coverage specialized/Coverage)
(def ReviewArtifact specialized/ReviewArtifact)
(def ReviewIssue specialized/ReviewIssue)
(def GateFeedback specialized/GateFeedback)
(def ReleaseArtifact specialized/ReleaseArtifact)
(def plan-summary specialized/plan-summary)
(def task-dependency-order specialized/task-dependency-order)
(def validate-plan specialized/validate-plan)
(def code-summary specialized/code-summary)
(def files-by-action specialized/files-by-action)
(def total-lines specialized/total-lines)
(def validate-code-artifact specialized/validate-code-artifact)
(def test-summary specialized/test-summary)
(def coverage-meets-threshold? specialized/coverage-meets-threshold?)
(def tests-by-path specialized/tests-by-path)
(def validate-test-artifact specialized/validate-test-artifact)
(def review-summary specialized/review-summary)
(def approved? specialized/approved?)
(def rejected? specialized/rejected?)
(def conditionally-approved? specialized/conditionally-approved?)
(def get-blocking-issues specialized/get-blocking-issues)
(def get-review-warnings specialized/get-review-warnings)
(def get-recommendations specialized/get-recommendations)
(def changes-requested? specialized/changes-requested?)
(def get-review-issues specialized/get-review-issues)
(def get-review-strengths specialized/get-review-strengths)
(def validate-review-artifact specialized/validate-review-artifact)
(def release-summary specialized/release-summary)
(def validate-release-artifact specialized/validate-release-artifact)

(def create-progress-monitor-agent meta/create-progress-monitor-agent)
(def create-meta-coordinator meta/create-meta-coordinator)
(def check-all-meta-agents meta/check-all-meta-agents)
(def reset-all-meta-agents! meta/reset-all-meta-agents!)
(def get-meta-check-history meta/get-meta-check-history)
(def get-meta-agent-stats meta/get-meta-agent-stats)
(def run-meta-loop-cycle! meta/run-meta-loop-cycle!)
(def create-meta-loop-context meta/create-meta-loop-context)
(def record-workflow-outcome! meta/record-workflow-outcome!)
(def run-cycle-from-context! meta/run-cycle-from-context!)

(def classify-task classification/classify-task)
(def get-task-characteristics classification/get-task-characteristics)

;------------------------------------------------------------------------------ Layer 3
;; File artifacts — working tree snapshot and fallback artifact collection

(def empty-snapshot
  "Return an empty working tree snapshot."
  file-artifacts/empty-snapshot)

(def collect-written-files
  "Collect files written during the agent session into a synthetic code artifact."
  file-artifacts/collect-written-files)

;------------------------------------------------------------------------------ Layer 3
;; Tool supervision

(def evaluate-tool-use
  "Evaluate a tool use request against policy.
   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}."
  tool-supervisor/evaluate-tool-use)

(def hook-eval-stdin!
  "CLI entry point for tool-use evaluation from stdin."
  tool-supervisor/hook-eval-stdin!)
