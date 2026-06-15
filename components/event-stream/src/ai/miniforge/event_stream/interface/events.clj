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

(def workflow-started
  "Build and return a :workflow/started event envelope map. Multi-arity
   for the legacy 2/3-arg call shape; the opts arity may carry
   :routing/trigger-event-id so the automation-edge-correlator links the
   handler workflow back to its routing trigger."
  core/workflow-started)

(def phase-started
  "Build and return a :workflow/phase-started event envelope map for the
   given phase keyword; optional context is attached as :phase/context."
  core/phase-started)

(def phase-completed
  "Build and return a :workflow/phase-completed event envelope map.
   Records :phase/outcome (default :success) and, when present on the
   result, duration, artifacts, error, transition-request, redirect-to,
   tokens, cost, meta, and termination-reason."
  core/phase-completed)

(def workspace-persisted
  "Build and return a :workspace/persisted event envelope map recording
   that a phase's worktree was archived to a checkpoint (phase, env-id,
   branch, commit-sha, bundle-path, persist-tier)."
  core/workspace-persisted)

(def agent-chunk
  "Build and return an :agent/chunk event envelope map carrying a
   streamed text delta for an agent; sets :chunk/done? when the optional
   done? flag is truthy."
  core/agent-chunk)

(def agent-status
  "Build and return an :agent/status event envelope map carrying a
   status-type keyword and human message for an agent."
  core/agent-status)

(def agent-tool-call
  "Build and return an :agent/tool-call event envelope map carrying the
   tool name(s) an agent invoked (:tool/name, :tool/names, :tool/call-id,
   :tool/args-preview). Legacy event kept for existing consumers."
  core/agent-tool-call)

(def agent-tool-call-started
  "Build and return an :agent/tool-call-started event envelope map
   marking the start of a tool call (:tool/name, :tool/names,
   :tool/args-digest, :tool/call-id); opens the latency span closed by
   tool-call-completed."
  core/agent-tool-call-started)

(def tool-call-completed
  "Build and return a :tool/call-completed event envelope map closing
   the latency span opened by agent-tool-call-started (:tool/call-id,
   :tool/result-digest, :tool/duration-ms, :tool/success?, :tool/error)."
  core/tool-call-completed)

(def phase-heartbeat
  "Build and return a :workflow/phase-heartbeat event envelope map for
   long-running phase liveness (:phase/active-since,
   :phase/events-emitted, :phase/last-event-at,
   :phase/gap-since-last-event-ms)."
  core/phase-heartbeat)

(def workflow-completed
  "Build and return a :workflow/completed event envelope map carrying a
   status keyword and optional duration plus opts (tokens, cost,
   pr-info, pr-infos, evidence-bundle-id)."
  core/workflow-completed)

(def workflow-failed
  "Build and return a :workflow/failed event envelope map. Normalizes
   the error (Throwable, anomaly map, or plain map) into
   :workflow/failure-reason, :workflow/error-details,
   :workflow/anomaly-code, and :workflow/retryable?; optional
   :failure/class."
  core/workflow-failed)

(def llm-request
  "Build and return an :llm/request event envelope map for an LLM call
   (model, optional prompt-tokens); assigns a fresh :llm/request-id."
  core/llm-request)

(def llm-response
  "Build and return an :llm/response event envelope map for an LLM
   response correlated by request-id; optional metrics add
   completion/total tokens, duration, and cost."
  core/llm-response)

(def agent-started
  "Build and return an :agent/started event envelope map; optional
   context is attached as :agent/context."
  core/agent-started)

(def agent-completed
  "Build and return an :agent/completed event envelope map; optional
   result is attached as :agent/result."
  core/agent-completed)

(def agent-failed
  "Build and return an :agent/failed event envelope map; optional error
   becomes :agent/error and optional :failure/class is carried through."
  core/agent-failed)

(def gate-started
  "Build and return a :gate/started event envelope map; optional
   artifact-summary is attached as :gate/artifact-summary."
  core/gate-started)

(def gate-passed
  "Build and return a :gate/passed event envelope map; optional
   duration-ms is attached as :gate/duration-ms."
  core/gate-passed)

(def gate-failed
  "Build and return a :gate/failed event envelope map; optional
   violations become :gate/violations and optional :failure/class is
   carried through."
  core/gate-failed)

(def gate-rule-applied
  "Build and return a :gate/rule-applied event envelope map: per-rule
   policy evidence recording rule-id evaluated in phase with status
   (:passed, :failed, :skipped-by-phase, :not-applicable). Optional
   extra carries :severity, :enforcement, and a NON-SENSITIVE
   :violation summary."
  core/gate-rule-applied)

(def tool-invoked
  "Build and return a :tool/invoked event envelope map for a tool an
   agent invoked; optional params-summary is attached as
   :tool/params-summary."
  core/tool-invoked)

(def tool-completed
  "Build and return a :tool/completed event envelope map; optional
   result-summary is attached as :tool/result-summary."
  core/tool-completed)

(def milestone-reached
  "Build and return a :workflow/milestone-reached event envelope map for
   a milestone-id; optional description overrides the default message."
  core/milestone-reached)

(def milestone-started
  "Build and return a :phase/milestone-started event envelope map for a
   milestone-id; optional description overrides the default message."
  core/milestone-started)

(def milestone-completed
  "Build and return a :phase/milestone-completed event envelope map for
   a milestone-id; optional description overrides the default message."
  core/milestone-completed)

(def milestone-failed
  "Build and return a :phase/milestone-failed event envelope map for a
   milestone-id; optional reason overrides the default message."
  core/milestone-failed)

(def task-state-changed
  "Build and return a :task/state-changed event envelope map recording a
   DAG task transitioning from-state to-state (:dag/id, :task/id,
   :task/from-state, :task/to-state); optional context as :task/context."
  core/task-state-changed)

(def task-frontier-entered
  "Build and return a :task/frontier-entered event envelope map for a
   DAG task entering the ready frontier; optional frontier-size as
   :task/frontier-size."
  core/task-frontier-entered)

(def task-skip-propagated
  "Build and return a :task/skip-propagated event envelope map for a DAG
   task skipped by upstream propagation; optional cause-task as
   :task/cause-task."
  core/task-skip-propagated)

(def inter-agent-message-sent
  "Build and return an :agent/message-sent event envelope map for a
   message from one agent to another (:from-agent/id, :to-agent/id);
   optional message-type as :message/type."
  core/inter-agent-message-sent)

(def inter-agent-message-received
  "Build and return an :agent/message-received event envelope map for a
   message received by an agent (:from-agent/id, :to-agent/id); optional
   message-type as :message/type."
  core/inter-agent-message-received)

(def listener-attached
  "Build and return a :listener/attached event envelope map for a
   listener id; optional listener-type and capability as :listener/type
   / :listener/capability."
  core/listener-attached)

(def listener-detached
  "Build and return a :listener/detached event envelope map for a
   listener id; optional reason as :listener/reason."
  core/listener-detached)

(def annotation-created
  "Build and return an :annotation/created event envelope map for an
   advisory annotation from a listener (:listener/id,
   :annotation/type); optional content as :annotation/content."
  core/annotation-created)

(def control-action-requested
  "Build and return a :control-action/requested event envelope map
   (:action/id, :action/type); optional requester as :action/requester."
  core/control-action-requested)

(def control-action-executed
  "Build and return a :control-action/executed event envelope map for an
   action id; optional result as :action/result."
  core/control-action-executed)

(def chain-started
  "Build and return a :chain/started event envelope map (chains are not
   workflow-scoped, so :workflow/id is nil) carrying :chain/id and
   :chain/step-count."
  core/chain-started)

(def chain-step-started
  "Build and return a :chain/step-started event envelope map carrying
   :chain/id, :step/id, :step/index, and :step/workflow-id."
  core/chain-step-started)

(def chain-step-completed
  "Build and return a :chain/step-completed event envelope map carrying
   :chain/id, :step/id, and :step/index."
  core/chain-step-completed)

(def chain-step-failed
  "Build and return a :chain/step-failed event envelope map carrying
   :chain/id, :step/id, :step/index, and :chain/error; optional
   :failure/class."
  core/chain-step-failed)

(def chain-completed
  "Build and return a :chain/completed event envelope map carrying
   :chain/id, :chain/duration-ms, and :chain/step-count."
  core/chain-completed)

(def chain-failed
  "Build and return a :chain/failed event envelope map carrying
   :chain/id, :chain/failed-step, and :chain/error; optional
   :failure/class."
  core/chain-failed)

;------------------------------------------------------------------------------ Layer 1
;; OCI container event constructors

(def container-started
  "Build and return an :oci/container-started event envelope map for a
   container id; optional opts add :oci/image-digest and
   :oci/trust-level."
  core/container-started)

(def container-completed
  "Build and return an :oci/container-completed event envelope map
   carrying :oci/container-id and :oci/exit-code; optional duration-ms
   as :oci/duration-ms."
  core/container-completed)

;------------------------------------------------------------------------------ Layer 1
;; Tool supervision event constructors

(def tool-use-evaluated
  "Build and return a :supervision/tool-use-evaluated event envelope map
   capturing a supervisor's decision on a tool-use request (:tool/name,
   :supervision/decision); optional opts add :supervision/reasoning,
   :supervision/meta-eval?, :supervision/confidence, :workflow/phase."
  core/tool-use-evaluated)

;------------------------------------------------------------------------------ Layer 1
;; Control plane event constructors

(def cp-agent-registered
  "Build and return a :control-plane/agent-registered event envelope map
   for an external agent registering with the control plane (:cp/agent-id,
   :cp/vendor); optional opts add name, external-id, capabilities,
   metadata, tags, heartbeat-interval-ms."
  core/cp-agent-registered)

(def cp-agent-heartbeat
  "Build and return a :control-plane/agent-heartbeat event envelope map
   (:cp/agent-id, :cp/status); optional opts add :cp/task and
   :cp/metrics."
  core/cp-agent-heartbeat)

(def cp-agent-state-changed
  "Build and return a :control-plane/agent-state-changed event envelope
   map recording an agent's normalized state transition (:cp/agent-id,
   :cp/from-status, :cp/to-status)."
  core/cp-agent-state-changed)

(def cp-decision-created
  "Build and return a :control-plane/decision-created event envelope map
   for an agent's decision request (:cp/agent-id, :cp/decision-id,
   :cp/summary); optional priority-or-opts add priority, type, context,
   options, deadline."
  core/cp-decision-created)

(def cp-decision-resolved
  "Build and return a :control-plane/decision-resolved event envelope
   map for a human-resolved decision (:cp/decision-id, :cp/resolution);
   optional comment as :cp/comment."
  core/cp-decision-resolved)

(def intervention-requested
  "Build and return a :supervisory/intervention-requested event envelope
   map, merging the intervention map into the envelope."
  core/intervention-requested)

(def intervention-state-changed
  "Build and return a :supervisory/intervention-state-changed event
   envelope map for an InterventionRequest lifecycle change
   (:intervention/id, :intervention/state); optional opts carry the
   many :intervention/* detail fields (from-state, type, target-type,
   target-id, requested-by, justification, outcome, ...)."
  core/intervention-state-changed)

;------------------------------------------------------------------------------ Layer 2
;; PR scoring event constructors (N5-delta-2 §4.1)

(def pr-created
  "Build and return a :pr/created event envelope map for a
   workflow-owned PR (:pr/repo, :pr/number, :pr/url, :pr/branch);
   optional title, author, merge-order. Lets consumers attach a PR to
   its owning workflow run."
  core/pr-created)

(def pr-scored
  "Build and return a :pr/scored event envelope map carrying PR scoring
   results (:pr/readiness, :pr/risk, :pr/policy, :pr/recommendation —
   any subset). Not inherently workflow-scoped; opts :workflow/id scopes
   it to a run."
  core/pr-scored)

;; Routing trigger event constructors (N5-delta-4 §4.2)
(def pr-monitor-review-comments-arrived
  "Build and return a :pr-monitor/review-comments-arrived event envelope
   map (:workflow/id nil — PR-scoped) for new review comments on an owned
   PR (:pr/repo, :pr/number, :comments/count); optional agent-session-id
   as :comments/agent-session-id."
  core/pr-monitor-review-comments-arrived)

(def pr-monitor-ci-failed
  "Build and return a :pr-monitor/ci-failed event envelope map
   (:workflow/id nil) for a CI status reaching a non-success terminal
   state on an owned PR (:pr/repo, :pr/number, :ci/check-name,
   :ci/conclusion). :ci/conclusion is an open keyword."
  core/pr-monitor-ci-failed)

(def standards-review-posted
  "Build and return a :standards-review/posted event envelope map
   (:workflow/id nil) for a standards-review comment on a PR (:pr/repo,
   :pr/number, :review/severity). :review/severity is an open keyword;
   optional affected-workflow-run-id as :affected/workflow-run-id."
  core/standards-review-posted)

;; Zettelkasten lifecycle event constructors (miniforge-fleet
;; Phase E.3 outbox path).

(def zettel-promoted
  "Build and return a :zettel/promoted event envelope map emitted when a
   zettel revision transitions to :trusted and becomes eligible to ride
   the Fleet event log. Carries the zettel's revision-keyed identity and
   content; Fleet-share intent (:fleet/shareable, :fleet/share-scope,
   :privacy/classification) rides through from the zettel when present.
   Args: stream, workflow-id, zettel map, oss-version; optional opts
   carry envelope identity (:org/id, :workspace/id, :repo/id,
   :auth/context)."
  core/zettel-promoted)

;------------------------------------------------------------------------------ Layer 2
;; Reliability metric event constructors (N3 §3.17)

(def sli-computed
  "Build and return a :reliability/sli-computed event envelope map
   (:workflow/id nil) for an SLI value computed over a rolling window
   (:sli/name, :sli/value, :sli/window); optional opts add :sli/tier and
   :sli/dimensions."
  core/sli-computed)

(def slo-breach
  "Build and return a :reliability/slo-breach event envelope map
   (:workflow/id nil) for a missed SLO target (:slo/sli-name,
   :slo/target, :slo/actual, :slo/tier, :slo/window)."
  core/slo-breach)

(def error-budget-update
  "Build and return a :reliability/error-budget-update event envelope
   map (:workflow/id nil) for recomputed error budget state
   (:budget/tier, :budget/sli, :budget/remaining, :budget/burn-rate,
   :budget/window)."
  core/error-budget-update)

(def dependency-health-updated
  "Build and return a :dependency/health-updated event envelope map
   (:workflow/id nil) for a changed dependency health projection,
   merging the dependency map; optional previous-status as
   :dependency/previous-status."
  core/dependency-health-updated)

(def dependency-recovered
  "Build and return a :dependency/recovered event envelope map
   (:workflow/id nil) for a dependency returning to healthy, merging the
   dependency map; optional previous-status as
   :dependency/previous-status."
  core/dependency-recovered)

(def degradation-mode-changed
  "Build and return a :reliability/degradation-mode-changed event
   envelope map (:workflow/id nil) for a degradation-mode transition
   (:degradation/from, :degradation/to, :degradation/trigger)."
  core/degradation-mode-changed)

(def safe-mode-entered
  "Build and return a :safe-mode/entered event envelope map (:workflow/id
   nil) recording safe-mode activation (:safe-mode/trigger); optional
   details as :safe-mode/trigger-details."
  core/safe-mode-entered)

(def safe-mode-exited
  "Build and return a :safe-mode/exited event envelope map (:workflow/id
   nil) recording safe-mode deactivation (:safe-mode/exited-by,
   :safe-mode/justification, :safe-mode/duration-ms,
   :safe-mode/workflows-queued)."
  core/safe-mode-exited)

;------------------------------------------------------------------------------ Layer 2
;; Meta-loop event constructors

(def meta-loop-cycle-completed
  "Build and return a :meta-loop/cycle-completed event envelope map
   (:workflow/id nil), merging the summary map (signals, diagnoses,
   proposals counts)."
  core/meta-loop-cycle-completed)

(def meta-loop-cycle-failed
  "Build and return a :meta-loop/cycle-failed event envelope map
   (:workflow/id nil) for an unhandled meta-loop exception
   (:meta-loop/error, :meta-loop/error-class)."
  core/meta-loop-cycle-failed)

;------------------------------------------------------------------------------ Layer 3
;; Observer / knowledge failure event constructors

(def observer-signal-failed
  "Build and return an :observer/signal-failed event envelope map for a
   failure forwarding a signal to the meta-loop (:observer/error)."
  core/observer-signal-failed)

(def knowledge-synthesis-failed
  "Build and return a :knowledge/synthesis-failed event envelope map
   (:workflow/id nil) for a pattern-synthesis failure (:knowledge/error)."
  core/knowledge-synthesis-failed)

(def knowledge-promotion-failed
  "Build and return a :knowledge/promotion-failed event envelope map
   (:workflow/id nil) for a learning-promotion failure
   (:knowledge/error)."
  core/knowledge-promotion-failed)

;------------------------------------------------------------------------------ Layer 3.5
;; Agent stream-stall and session event constructors (GROUP 1+4, GROUP 2)

(def agent-stream-stalled
  "Build and return an :agent/stream-stalled event envelope map
   indicating the agent output stream has gone silent past the gap
   threshold (:workflow/phase, :stream/gap-duration-ms, :agent/backend).
   Consumed by the self-healing supervisor."
  core/agent-stream-stalled)

(def agent-session-captured
  "Build and return an :agent/session-captured event envelope map
   recording the backend session id from the initial handshake
   (:workflow/phase, :agent/backend, :agent/session-id). Must be emitted
   before the first tool call so resume-on-kill has a valid session id."
  core/agent-session-captured)
;------------------------------------------------------------------------------ Layer 9.5
;; Repository index intelligence event constructors (RN-19/20)

(def repo-index-quality-measured
  "Build and return a :repo-index/quality-measured event envelope map
   (RN-19) for a re-scored index entry (:index/id, :index/quality-score,
   :index/staleness-ms, :index/coverage); optional :tree-sha as
   :index/tree-sha."
  core/repo-index-quality-measured)

(def repo-index-coverage-changed
  "Build and return a :repo-index/coverage-changed event envelope map
   (RN-20) for a material change in tracked-file coverage (:index/id,
   :index/coverage, :index/previous-coverage); optional :tree-sha as
   :index/tree-sha."
  core/repo-index-coverage-changed)
