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
   [ai.miniforge.agent.interface.supervision :as supervision]
   [ai.miniforge.agent.interface.watchdog :as watchdog]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.tool-supervisor :as tool-supervisor]))

;------------------------------------------------------------------------------ Layer 0
;; Protocols and configuration

(def Agent
  "Protocol: agent behavior and lifecycle. Methods invoke/validate/repair.
   Implement to define a custom agent that processes tasks into
   artifacts/decisions/signals."
  protocols/Agent)
(def AgentLifecycle
  "Protocol: agent lifecycle management (init/status/shutdown/abort).
   Implement to control how an agent is initialized and torn down."
  protocols/AgentLifecycle)
(def AgentExecutor
  "Protocol: runs an agent on a task through the full
   init->invoke->validate->repair->complete lifecycle. Implement to
   customize execution orchestration."
  protocols/AgentExecutor)
(def LLMBackend
  "Protocol: LLM interaction (single method complete). Implement to swap
   or mock the language-model backend."
  protocols/LLMBackend)
(def Memory
  "Protocol: agent memory storage and retrieval (add-message, get-messages,
   get-window, clear-messages, get-metadata). Implement for a custom store."
  protocols/Memory)
(def MemoryStore
  "Protocol: managing multiple agent memories (get/save/delete/list).
   Implement to back memory persistence with a custom store."
  protocols/MemoryStore)
(def InterAgentMessaging
  "Protocol: send/receive/respond messages between agents during execution.
   Implement to customize how an agent exchanges messages."
  protocols/InterAgentMessaging)
(def MessageRouter
  "Protocol: routes and queues messages between agents
   (route-message, get-messages-for-agent, get-messages-by-workflow,
   clear-messages). Implement for a custom delivery mechanism."
  protocols/MessageRouter)
(def MessageValidator
  "Protocol: validates message structure against schema (validate-message).
   Implement to plug in custom message validation."
  protocols/MessageValidator)
(def default-role-configs
  "Map of agent role keyword -> default config map. Each config carries
   static fields (temperature, max-tokens, budget) from
   role-defaults.edn plus a dynamically selected :model."
  protocols/default-role-configs)
(def role-capabilities
  "Map of agent role keyword -> set of capability keywords
   (e.g. :planner -> #{:plan :decompose :estimate})."
  protocols/role-capabilities)
(def role-system-prompts
  "Map of agent role keyword -> system-prompt string for that role."
  protocols/role-system-prompts)

;------------------------------------------------------------------------------ Layer 1
;; Runtime and memory API

(def create-agent
  "Create an agent by role with optional config overrides. Args: role
   (keyword e.g. :planner :implementer), optional opts map (:model
   :temperature :max-tokens :budget :phase :privacy-required etc.).
   Returns a BaseAgent record satisfying the Agent protocol."
  runtime/create-agent)
(def create-agent-map
  "Create an agent and return it as a plain map conforming to the Agent
   schema (useful for serialization/storage). Args: role, optional opts.
   Returns a map with :agent/* keys."
  runtime/create-agent-map)
(def create-executor
  "Create an agent executor. Arg (optional): config map (e.g.
   {:memory-store ...}). Returns a value satisfying the AgentExecutor
   protocol."
  runtime/create-executor)
(def execute
  "Run an agent on a task through the full lifecycle. Args: executor, agent,
   task, context. Thin delegate to the AgentExecutor protocol: returns the
   executor's result map, which is the agent's invoke result (commonly the
   canonical {:status :success|:error :output ...}) merged with executor
   metadata (:metrics :tool/invocations :agent-id :task-id :executed-at).
   Exact keys depend on the agent and executor implementations."
  runtime/execute)
(def invoke
  "Invoke an agent's main logic on a task, with supervisory projection
   wrapping. Args: agent, task, context. Thin delegate to the Agent protocol's
   invoke: returns the agent's result map (commonly the canonical
   {:status :success|:error :output ...}, optionally augmented by the
   supervisory projection). Exact keys depend on the agent implementation."
  runtime/invoke)
(def validate
  "Check whether agent output meets requirements. Args: agent, output,
   context. Returns {:valid? bool :errors [...] :warnings [...]}."
  runtime/validate)
(def repair
  "Attempt to fix validation failures. Args: agent, output, errors, context.
   Thin delegate to the Agent protocol's repair: returns the agent's repair
   result map (commonly the canonical {:status :success|:error :output ...}).
   Exact keys depend on the agent implementation."
  runtime/repair)
(def init
  "Initialize an agent with configuration. Args: agent, config. Returns the
   initialized agent instance."
  runtime/init)
(def agent-status
  "Return an agent's current status. Arg: agent. Returns
   {:state keyword :metrics {...} :last-activity inst}."
  runtime/agent-status)
(def shutdown
  "Clean up agent resources. Arg: agent. Returns {:success bool}."
  runtime/shutdown)

(def create-memory
  "Create a new agent memory. Arg (optional): {:scope :scope-id :metadata};
   :scope defaults to :task. Returns an AgentMemory (a Memory) with an
   empty message list."
  memory/create-memory)
(def add-to-memory
  "Append a message to memory under the given role. Args: memory, role
   (keyword), content. Returns the updated memory."
  memory/add-to-memory)
(def add-system-message
  "Append a :system message to memory. Args: memory, content (string).
   Returns the updated memory."
  memory/add-system-message)
(def add-user-message
  "Append a :user message to memory. Args: memory, content (string).
   Returns the updated memory."
  memory/add-user-message)
(def add-assistant-message
  "Append an :assistant message to memory. Args: memory, content (string),
   optional :tokens. Returns the updated memory."
  memory/add-assistant-message)
(def get-messages
  "Return all messages in memory in chronological order (a sequence of
   message maps). Arg: memory."
  memory/get-messages)
(def get-memory-window
  "Return the most recent messages fitting within token-limit. Args: memory,
   token-limit (int). Returns {:messages [...] :total-tokens int
   :trimmed-count int}; system messages are always preserved."
  memory/get-memory-window)
(def clear-memory
  "Remove all messages from memory. Arg: memory. Returns the emptied memory."
  memory/clear-memory)
(def memory-metadata
  "Return memory metadata. Arg: memory. Returns a map merging stored
   metadata with :memory/id :memory/scope :memory/message-count
   :memory/total-tokens."
  memory/memory-metadata)
(def create-memory-store
  "Create an in-memory MemoryStore backed by an atom. No args.
   Returns an InMemoryStore (a MemoryStore)."
  memory/create-memory-store)
(def get-memory
  "Retrieve a memory from a store by id. Args: store, memory-id.
   Returns the memory, or nil if absent."
  memory/get-memory)
(def save-memory
  "Save/update a memory in a store. Args: store, memory. Returns the memory."
  memory/save-memory)
(def delete-memory
  "Delete a memory from a store by id. Args: store, memory-id. Returns nil."
  memory/delete-memory)
(def list-memories
  "List memories in a store matching a scope. Args: store, scope, scope-id.
   Returns a sequence of memories."
  memory/list-memories)

;------------------------------------------------------------------------------ Layer 2
;; Messaging, utilities, specialized agents, and meta-operations

(def create-message-router
  "Create a message router. No args. Returns a MessageRouter record backed by
   atoms for per-workflow messages and per-agent inboxes."
  messaging/create-message-router)
(def create-agent-messaging
  "Create messaging capability for an agent. Args: agent-id (keyword),
   instance-id (uuid), workflow-id (uuid), router, optional event-stream.
   Returns an AgentMessaging record (an InterAgentMessaging)."
  messaging/create-agent-messaging)
(def send-message
  "Send a message from agent-messaging to the target specified in message-data.
   Event emission is handled by the impl layer."
  messaging/send-message)
(def receive-messages
  "Return all messages received by this agent. Arg: agent-messaging.
   Returns a sequence of message maps."
  messaging/receive-messages)
(def respond-to-message
  "Respond to a message. Event emission is handled by the impl layer."
  messaging/respond-to-message)
(def send-clarification-request
  "Send a :clarification-request to another agent. Args: agent-messaging,
   to-agent (keyword), content (string). Returns {:message map :event map}."
  messaging/send-clarification-request)
(def send-concern
  "Send a :concern to another agent. Args: agent-messaging, to-agent
   (keyword), content (string). Returns {:message map :event map}."
  messaging/send-concern)
(def send-suggestion
  "Send a :suggestion to another agent. Args: agent-messaging, to-agent
   (keyword), content (string). Returns {:message map :event map}."
  messaging/send-suggestion)
(def get-clarification-requests
  "Return received messages of type :clarification-request. Arg:
   agent-messaging. Returns a sequence of message maps."
  messaging/get-clarification-requests)
(def get-concerns
  "Return received messages of type :concern. Arg: agent-messaging.
   Returns a sequence of message maps."
  messaging/get-concerns)
(def get-suggestions
  "Return received messages of type :suggestion. Arg: agent-messaging.
   Returns a sequence of message maps."
  messaging/get-suggestions)
(def route-message
  "Route a message to its recipient."
  messaging/route-message)
(def get-messages-for-agent
  "Return messages for a specific agent in a workflow. Args: router, agent-id,
   workflow-id. Returns a sequence of message maps (the agent's inbox)."
  messaging/get-messages-for-agent)
(def get-messages-by-workflow
  "Return all messages in a workflow, ordered by timestamp. Args: router,
   workflow-id. Returns a sequence of message maps."
  messaging/get-messages-by-workflow)
(def clear-workflow-messages
  "Clear all messages for a workflow. Args: router, workflow-id. Returns nil."
  messaging/clear-workflow-messages)
(def validate-message
  "Validate a message map against the Message schema. Arg: message.
   Returns {:valid? boolean :errors [...]}."
  messaging/validate-message)
(def Message
  "Malli schema (a [:map ...] vector) for an inter-agent message, per N1
   §6.2.2. Required keys include :message/id :message/type :message/from-agent
   :message/to-agent :message/workflow-id :message/content :message/timestamp."
  messaging/Message)
(def MessageType
  "Malli schema (a [:enum ...] vector) of valid message types:
   :clarification-request :clarification-response :concern :suggestion."
  messaging/MessageType)

(def estimate-tokens
  "Estimate token count for message content. Arg: content (any). Returns an
   int: for strings, max(1, chars/4); 100 for non-string content."
  llm/estimate-tokens)
(def estimate-cost
  "Estimate cost in USD for token usage. Args: input-tokens, output-tokens,
   model (string). Returns a double; unknown/unpriced models return 0.0."
  llm/estimate-cost)
(def create-mock-llm
  "Create a mock LLMBackend record for testing. Arg (optional): a single
   response map or a sequence of them; defaults to a canned response.
   Returns a value satisfying the LLMBackend protocol."
  llm/create-mock-llm)

(def create-base-agent
  "Create a base (functional) agent from callbacks. Arg: options map requiring
   :role :system-prompt :invoke-fn :validate-fn :repair-fn, with optional
   :config :logger. Returns a FunctionalAgent record (an Agent)."
  specialized/create-base-agent)
(def make-validator
  "Build a validate-fn from a Malli schema. Arg: malli-schema. Returns a fn of
   output -> {:valid? bool :errors nil-or-explanation}."
  specialized/make-validator)
(def cycle-agent
  "Run an invoke->validate->repair cycle on a specialized agent. Args: agent,
   context, input, optional :max-iterations (default 3). Returns the final
   result map after repair attempts."
  specialized/cycle-agent)
(def create-planner
  "Create a Planner agent. Arg (optional): options map (:config :logger
   :llm-backend). Returns a FunctionalAgent record (an Agent)."
  specialized/create-planner)
(def create-implementer
  "Create an Implementer agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  specialized/create-implementer)
(def create-tester
  "Create a Tester agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  specialized/create-tester)
(def create-reviewer
  "Create a Reviewer agent (LLM-backed review + deterministic gates, falling
   back to gate-only without an LLM). Arg (optional): options map
   (:gates :strict :logger :llm-backend :config). Returns a FunctionalAgent
   record (an Agent)."
  specialized/create-reviewer)
(def create-releaser
  "Create a Releaser agent. Arg (optional): options map. Returns a
   FunctionalAgent record (an Agent)."
  specialized/create-releaser)
(def curate
  "Curator entry point (a multimethod dispatching on :curator/kind). Arg:
   input map. Returns a response map whose :output is a CuratedArtifact on
   success, or an error response. Dispatch: :implement (default) and
   :merge-resolution."
  specialized/curate)
(def curate-implement-output
  "Curate an implementer's result + environment state into a CuratedArtifact.
   Arg: input map (requires :implementer-result, :worktree-path; see impl for
   capsule-mode keys). Returns a success response with :output a
   CuratedArtifact, or an error response when no files were written. Thin
   wrapper over (curate (assoc input :curator/kind :implement))."
  specialized/curate-implement-output)
(def CuratedArtifact
  "Malli schema (a [:map ...] vector) for the curator's structured output,
   consumed by verify/review/release/PR-doc phases. Keys include :code/id
   :code/files :code/summary :code/tests-added? :code/scope-deviations
   :code/breaking-change? :code/curated-at :code/curator-source."
  specialized/CuratedArtifact)
(def CuratedFileEntry
  "Malli schema (a [:map ...] vector) for one file in a CuratedArtifact:
   :path (string) :content (string) :action (:create|:modify|:delete)."
  specialized/CuratedFileEntry)
(def validate-curated-artifact
  "Validate a CuratedArtifact against its schema. Arg: artifact. Returns a
   validation result (schema/valid or schema/invalid map)."
  specialized/validate-curated-artifact)
(def substantive-file?
  "True when a file entry represents real implementer work, not a
   runtime/session side-effect. Arg: file-entry map. Returns boolean."
  specialized/substantive-file?)
(def non-substantive-paths
  "Set of file paths the curator drops before assessing 'files written'
   (runtime/session markers). A set of strings (currently
   #{\".miniforge-session-id\"}), not a vector."
  specialized/non-substantive-paths)
(def Plan
  "Malli schema (a [:map ...] vector) for a planner's output: :plan/id
   :plan/name :plan/tasks (vector of PlanTask) plus optional complexity,
   risks, assumptions."
  specialized/Plan)
(def PlanTask
  "Malli schema (a [:map ...] vector) for one task in a Plan: :task/id
   :task/description :task/type plus optional dependencies, acceptance
   criteria, effort, component, stratum, merge-strategy."
  specialized/PlanTask)
(def CodeArtifact
  "Malli schema (a [:map ...] vector) for the implementer's output: :code/id
   :code/files (vector of CodeFile) plus optional dependencies-added,
   tests-needed?, language, summary."
  specialized/CodeArtifact)
(def CodeFile
  "Malli schema (a [:map ...] vector) for one code file: :path :content
   :action (:create|:modify|:delete)."
  specialized/CodeFile)
(def TestArtifact
  "Malli schema (a [:map ...] vector) for the tester's output: :test/id
   :test/files (vector of TestFile) :test/type plus optional coverage,
   framework, assertions/cases counts, summary."
  specialized/TestArtifact)
(def TestFile
  "Malli schema (a [:map ...] vector) for one test file: :path :content."
  specialized/TestFile)
(def Coverage
  "Malli schema (a [:map ...] vector) for test coverage: optional :lines
   :branches :functions, each a double 0.0-100.0."
  specialized/Coverage)
(def ReviewArtifact
  "Malli schema (a [:map ...] vector) for the reviewer's output: :review/id
   :review/decision (enum) :review/gate-results :review/summary plus gate
   counts and optional blocking-issues, warnings, recommendations, issues,
   strengths."
  specialized/ReviewArtifact)
(def ReviewIssue
  "Malli schema (a [:map ...] vector) for a single review issue: :severity
   (:blocking|:warning|:nit) :description plus optional :file :line
   :suggestion (each nil-tolerant)."
  specialized/ReviewIssue)
(def GateFeedback
  "Malli schema (a [:map ...] vector) for feedback from one gate: :gate-id
   :gate-type :passed? plus optional errors, warnings, duration-ms."
  specialized/GateFeedback)
(def ReleaseArtifact
  "Malli schema (a [:map ...] vector) for the releaser's output:
   :release/id :release/branch-name :release/commit-message :release/pr-title
   :release/pr-description plus optional files-summary."
  specialized/ReleaseArtifact)
(def plan-summary
  "Summarize a plan for logging/display. Arg: plan. Returns a map
   {:id :name :task-count :complexity :risk-count}."
  specialized/plan-summary)
(def task-dependency-order
  "Topologically sort a plan's tasks (dependency-free first). Arg: plan.
   Returns a vector of task maps; on a cycle, remaining tasks are appended."
  specialized/task-dependency-order)
(def validate-plan
  "Validate a plan against the Plan schema and check structural issues
   (invalid/circular deps). Arg: plan. Returns {:valid? bool :errors ...}."
  specialized/validate-plan)
(def code-summary
  "Summarize a code artifact for logging/display. Arg: artifact. Returns a
   map {:id :file-count :actions :language :tests-needed? :dependencies-added}."
  specialized/code-summary)
(def files-by-action
  "Group a code artifact's files by :action. Arg: artifact. Returns a map of
   action keyword -> vector of file maps."
  specialized/files-by-action)
(def total-lines
  "Count total lines of code in an artifact (excluding :delete files). Arg:
   artifact. Returns an int."
  specialized/total-lines)
(def validate-code-artifact
  "Validate a code artifact against the schema and check for issues (empty
   creates, duplicate paths). Arg: artifact. Returns a validation result
   (schema/valid or schema/invalid map)."
  specialized/validate-code-artifact)
(def test-summary
  "Summarize a test artifact for logging/display. Arg: artifact. Returns a
   map {:id :file-count :type :framework :assertions :cases :coverage}."
  specialized/test-summary)
(def coverage-meets-threshold?
  "Check whether an artifact's coverage meets thresholds. Args: artifact,
   optional :lines :branches :functions (defaults 80/70/80). Returns boolean."
  specialized/coverage-meets-threshold?)
(def tests-by-path
  "Map a test artifact's file paths to their content. Arg: artifact. Returns
   a map of path string -> content string."
  specialized/tests-by-path)
(def validate-test-artifact
  "Validate a test artifact against the schema and check for issues (naming
   convention, missing assertions). Arg: artifact. Returns
   {:valid? bool :errors ...}."
  specialized/validate-test-artifact)
(def review-summary
  "Summarize a review artifact for logging/display. Arg: artifact. Returns a
   map {:id :decision :gates-passed :gates-failed :gates-total
   :blocking-issues-count :warnings-count :llm-issues-count}."
  specialized/review-summary)
(def approved?
  "True when a review artifact's :review/decision is :approved. Arg: artifact.
   Returns boolean."
  specialized/approved?)
(def rejected?
  "True when a review artifact's :review/decision is :rejected. Arg: artifact.
   Returns boolean."
  specialized/rejected?)
(def conditionally-approved?
  "True when a review artifact's :review/decision is :conditionally-approved.
   Arg: artifact. Returns boolean."
  specialized/conditionally-approved?)
(def get-blocking-issues
  "Extract blocking issues from a review artifact. Arg: artifact. Returns a
   vector of strings (empty if none)."
  specialized/get-blocking-issues)
(def get-review-warnings
  "Extract warnings from a review artifact. Arg: artifact. Returns a vector of
   strings (empty if none)."
  specialized/get-review-warnings)
(def get-recommendations
  "Extract recommendations from a review artifact. Arg: artifact. Returns a
   vector of strings (empty if none)."
  specialized/get-recommendations)
(def changes-requested?
  "True when a review artifact's :review/decision is :changes-requested. Arg:
   artifact. Returns boolean."
  specialized/changes-requested?)
(def get-review-issues
  "Extract LLM review issues from a review artifact. Arg: artifact. Returns a
   vector of ReviewIssue maps (empty if none)."
  specialized/get-review-issues)
(def get-review-strengths
  "Extract strengths noted by the LLM from a review artifact. Arg: artifact.
   Returns a vector of strings (empty if none)."
  specialized/get-review-strengths)
(def validate-review-artifact
  "Validate a review artifact against the schema and check gate-count
   consistency. Arg: artifact. Returns {:valid? bool :errors ...}."
  specialized/validate-review-artifact)
(def rejection-warnings-only?
  "True when a review is a rejection driven solely by warnings (no blocking
   issues). Arg: artifact. Returns boolean."
  specialized/rejection-warnings-only?)
(def review-fingerprint
  "Reduce a review artifact to a stable, comparable fingerprint of its
   actionable items. Arg: review. Returns a sorted vector of
   [severity file line description] tuples (empty when nothing is actionable)."
  specialized/review-fingerprint)
(def review-stagnated?
  "True when a review's fingerprint exactly matches the prior one (repair
   loop made no actionable change). Args: prior (may be nil), current.
   Returns boolean; false on first iteration or empty current fingerprint."
  specialized/review-stagnated?)
(def release-summary
  "Summarize a release artifact for logging/display. Arg: artifact. Returns a
   map {:id :branch :pr-title :files-summary}."
  specialized/release-summary)
(def validate-release-artifact
  "Validate a release artifact against the schema and check for issues (branch
   name spaces, PR-title length, empty commit first line). Arg: artifact.
   Returns {:valid? bool :errors ...}."
  specialized/validate-release-artifact)

(def create-progress-monitor-agent
  "Create a Progress Monitor meta-agent. Arg (optional): options map
   (:check-interval-ms :priority :stagnation-threshold-ms :max-total-ms).
   Returns a meta-agent suitable for a meta coordinator."
  meta/create-progress-monitor-agent)
(def create-meta-coordinator
  "Create a meta-agent coordinator over a set of meta-agents. Arg: vector of
   meta-agent instances. Returns a MetaCoordinator record."
  meta/create-meta-coordinator)
(def check-all-meta-agents
  "Run due health checks across all enabled meta-agents. Args: coordinator,
   workflow-state. Returns {:status :healthy|:warning|:halt :checks [...]}
   with :halt-reason/:halting-agent when status is :halt."
  meta/check-all-meta-agents)
(def reset-all-meta-agents!
  "Reset all meta-agent state (on workflow restart). Arg: coordinator.
   Returns the coordinator."
  meta/reset-all-meta-agents!)
(def get-meta-check-history
  "Return recent meta-agent health-check history. Args: coordinator, optional
   {:limit :agent-id :status}. Returns a sequence of check-record maps."
  meta/get-meta-check-history)
(def get-meta-agent-stats
  "Return per-meta-agent statistics. Arg: coordinator. Returns
   {:agents [{:id :name :checks-run :last-check :last-status
              :halt-count :warning-count ...}]}."
  meta/get-meta-agent-stats)
(def create-supervision-coordinator
  "Create a supervision coordinator over a set of supervisor agents. Arg:
   vector of meta-agent instances. Returns a MetaCoordinator record."
  supervision/create-supervision-coordinator)
(def check-all-supervisors
  "Run due health checks across all enabled supervisors. Args: coordinator,
   workflow-state. Returns {:status :healthy|:warning|:halt :checks [...]}
   with :halt-reason/:halting-agent when status is :halt."
  supervision/check-all-supervisors)
(def reset-all-supervisors!
  "Reset all supervisor agent state (on workflow restart). Arg: coordinator.
   Returns the coordinator."
  supervision/reset-all-supervisors!)
(def get-supervision-check-history
  "Return recent supervisor health-check history. Args: coordinator, optional
   {:limit :agent-id :status}. Returns a sequence of check-record maps."
  supervision/get-supervision-check-history)
(def get-supervisor-stats
  "Return per-supervisor statistics. Arg: coordinator. Returns
   {:agents [{:id :name :checks-run :last-check :last-status
              :halt-count :warning-count ...}]}."
  supervision/get-supervisor-stats)
(def run-meta-loop-cycle!
  "Execute one meta-loop learning cycle.

   Orchestrates: reliability compute → degradation eval → diagnosis → improvement proposals.

   Arguments:
     ctx - {:event-stream :reliability-engine :degradation-manager
            :improvement-pipeline :metrics :training-examples}

   Returns: {:cycle/sli-count :cycle/breach-count :cycle/signal-count
             :cycle/diagnosis-count :cycle/proposal-count :cycle/degradation-mode ...}"
  meta/run-meta-loop-cycle!)
(def create-meta-loop-context
  "Create a MetaLoopContext bundling reliability engine, degradation manager,
   improvement pipeline, and metrics store.

   Arguments:
     event-stream - event stream atom
     config       - optional {:reliability {} :degradation {}}"
  meta/create-meta-loop-context)
(def record-workflow-outcome!
  "Record a workflow completion for use in the next meta-loop cycle.

   Arguments:
     ctx           - MetaLoopContext
     workflow-id   - uuid
     status        - :completed | :failed | :escalated
     failure-class - optional :failure.class/* keyword"
  meta/record-workflow-outcome!)
(def run-cycle-from-context!
  "Run one meta-loop cycle using the accumulated metrics in ctx.

   Arguments:
     ctx               - MetaLoopContext
     training-examples - optional vector of training records

   Returns: cycle result map."
  meta/run-cycle-from-context!)

(def classify-task
  "Classify a task to pick an optimal model type.
   Arg: task map with optional :phase :agent-type :description :title and
   sizing/privacy/cost hints. Returns a map
   {:type keyword :confidence double :reason string
    :alternative-types [keyword] :all-reasons [string]}; never nil
   (defaults to :execution-focused at 0.5 confidence)."
  classification/classify-task)
(def get-task-characteristics
  "Extract the features used during classification, for debugging/transparency.
   Arg: task map. Returns a map
   {:phase :agent-type :keywords #{kw} :context-size int
    :privacy-required truthy-or-nil :cost-constrained truthy-or-nil}."
  classification/get-task-characteristics)

;------------------------------------------------------------------------------ Layer 3
;; File artifacts — working tree snapshot and fallback artifact collection

(def empty-snapshot
  "Return an empty working tree snapshot."
  file-artifacts/empty-snapshot)

(def collect-written-files
  "Collect files written during the agent session into a synthetic code artifact."
  file-artifacts/collect-written-files)

(def collect-worktree-files
  "Collect the promoted task artifact from the current worktree."
  file-artifacts/collect-worktree-files)

(def rehydrate-files
  "Reconstitute :code/files from recorded paths by reading the worktree.
   Used by downstream phases when the implement phase persisted only
   paths-as-metadata and the worktree has since been committed clean."
  file-artifacts/rehydrate-files)

;------------------------------------------------------------------------------ Layer 4
;; Tool supervision

(def evaluate-tool-use
  "Evaluate a tool use request against policy.
   Returns {:decision \"allow\"} or {:decision \"deny\" :reason string}."
  tool-supervisor/evaluate-tool-use)

(def hook-eval-stdin!
  "CLI entry point for tool-use evaluation from stdin."
  tool-supervisor/hook-eval-stdin!)

;------------------------------------------------------------------------------ Layer 5
;; Stream-gap watchdog
;;
;; Note on naming: functions exported from the watchdog sub-interface
;; (ai.miniforge.agent.interface.watchdog) keep their natural short names
;; (ping!, stop!, stalled?, capture-session-id!, get-session-id). At this
;; top-level interface the verb-style names ping!/stop!/stalled? would
;; collide with unrelated lifecycle verbs in this namespace, so we apply a
;; watchdog- prefix (ping-watchdog!, stop-watchdog!, watchdog-stalled?,
;; capture-watchdog-session-id!, get-watchdog-session-id). Callers that
;; need only watchdog functionality should depend on
;; ai.miniforge.agent.interface.watchdog directly to avoid the prefix.

(def resolve-gap-threshold
  "Resolve the stream-gap threshold (ms) for a backend.
   See `ai.miniforge.agent.stream-watchdog/resolve-gap-threshold`."
  watchdog/resolve-gap-threshold)
(def default-gap-threshold-ms
  "Default stream-gap threshold in ms (90 000)."
  watchdog/default-gap-threshold-ms)
(def default-check-interval-ms
  "Default watchdog check interval in ms (5 000)."
  watchdog/default-check-interval-ms)
(def create-watchdog
  "Create and start a stream-gap watchdog.
   See `ai.miniforge.agent.stream-watchdog/create-watchdog`."
  watchdog/create-watchdog)
(def ping-watchdog!
  "Record a new agent event, resetting the gap timer.
   Named ping! (not reset!) to avoid shadowing clojure.core/reset!.
   See `ai.miniforge.agent.stream-watchdog/ping!`."
  watchdog/ping!)
(def stop-watchdog!
  "Gracefully shut down the watchdog scheduler.
   See `ai.miniforge.agent.stream-watchdog/stop!`."
  watchdog/stop!)
(def watchdog-stalled?
  "Return true if the watchdog fired and killed the agent subprocess.
   See `ai.miniforge.agent.stream-watchdog/stalled?`."
  watchdog/stalled?)
(def watchdog-stall-gap-ms
  "Return the measured stream gap (ms) at the moment the watchdog fired, or nil.
   See `ai.miniforge.agent.stream-watchdog/stall-gap-ms`."
  watchdog/stall-gap-ms)
(def capture-watchdog-session-id!
  "Parse and persist the session ID from the initial agent handshake event.
   Emits :agent/session-captured via event-stream.
   See `ai.miniforge.agent.stream-watchdog/capture-session-id!`."
  watchdog/capture-session-id!)
(def get-watchdog-session-id
  "Return the captured session ID string, or nil if not yet captured.
   See `ai.miniforge.agent.stream-watchdog/get-session-id`."
  watchdog/get-session-id)

;------------------------------------------------------------------------------ Layer 6
;; Phase-result-level timeout predicates
;;
;; `stream-idle-error?` / `network-drop-error?` / `backend-timeout-error?` read
;; the NORMALIZED boundary (output of `normalize-llm-result`). The predicates
;; below read PHASE RESULT MAPS — the fully-assembled results returned by a
;; role's invoke-fn (e.g. what `timeout-only-error-result` and
;; `build-review-result` return). Phase-software-factory holds the phase result,
;; not the intermediate normalized map; these predicates let it branch on
;; infra-timeout results without reaching into role internals.
;;
;; Design note — why two levels?
;; The 2026-06-05 dogfood (adhoc-944448986) showed that predicting timeout from
;; the PARSED review (`timeout-only-review?`) misses the nil-parse path: when
;; the LLM errors before producing content there is nothing to parse, so the
;; parsed-review predicate silently returns false and the framework synthesizes
;; a false `:rejected`. The phase-result predicates here catch that path at the
;; role boundary BEFORE any parse attempt, routing infra timeouts to
;; `timeout-only-error-result` (infra exit) instead of a synthesized rejection.

(def stream-idle-in-result?
  "True when a phase result map (output of a role's invoke-fn, NOT a
   normalized boundary) carries a stream-idle indicator.

   Uses a three-path cascade (most-to-least specific):
   1. `[:error :data :timeout :type]` — primary: standard production shape
      for any role that calls `error-response` / `timeout-only-error-result`.
   2. `[:timeout :type]` — secondary: direct on the result map for alternate
      error shapes that skip the `:error/:data` wrapper.
   3. Message-channel string markers on `[:error :type]` / `[:type]`
      containing 'stream-idle' — covers JSON/transit round-trips where
      keywords serialise to strings.

   Use when the consumer holds the fully-assembled phase result (e.g.
   phase-software-factory inspecting a reviewer or implementer result for
   infra-timeout routing). Distinct from `stream-idle-error?`, which reads
   the normalized boundary (result of `normalize-llm-result`).

   Symmetric twin of `network-drop-in-result?`."
  result-boundary/stream-idle-in-result?)

(def network-drop-in-result?
  "True when a phase result map (output of a role's invoke-fn, NOT a
   normalized boundary) carries a network-drop indicator.

   Same three-path cascade as `stream-idle-in-result?`, checking for
   `:network-drop` type. Route to the auto-resumer (PR-C) rather than
   the backend-timeout retry path when this fires.

   Symmetric twin of `stream-idle-in-result?`."
  result-boundary/network-drop-in-result?)
