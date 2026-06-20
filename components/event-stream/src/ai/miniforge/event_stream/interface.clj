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

(ns ai.miniforge.event-stream.interface
  "Canonical public boundary for the event-stream component.
   Public API groups live under ai.miniforge.event-stream.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.event-stream.interface.approval :as approval]
   [ai.miniforge.event-stream.interface.callbacks :as callbacks]
   [ai.miniforge.event-stream.interface.control :as control]
   [ai.miniforge.event-stream.interface.control-state :as control-state]
   [ai.miniforge.event-stream.interface.events :as events]
   [ai.miniforge.event-stream.interface.listeners :as listeners]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.digest :as digest]
   [ai.miniforge.event-stream.heartbeat :as heartbeat]
   [ai.miniforge.event-stream.reader :as reader]
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.timeline :as timeline]))

;------------------------------------------------------------------------------ Layer 0
;; Stream lifecycle and control state

(def create-control-state
  "Create a control-state atom for driving workflow pause/resume/cancel
   from the CLI poller or TUI. Returns an atom holding
   {:paused false :stopped false :adjustments {}}."
  control-state/create-control-state)
(def pause!
  "Set the control-state atom's :paused flag true. Returns the new
   state map."
  control-state/pause!)
(def resume!
  "Clear the control-state atom's :paused flag (set false). Returns the
   new state map."
  control-state/resume!)
(def cancel!
  "Set the control-state atom's :stopped flag true. Returns the new
   state map."
  control-state/cancel!)
(def paused?
  "Return the boolean :paused flag from a control-state atom."
  control-state/paused?)
(def cancelled?
  "Return the boolean :stopped flag from a control-state atom."
  control-state/cancelled?)

(def create-event-stream
  "Create an event stream. Returns an atom holding the stream state
   (events vector, subscribers, filters, sinks, sequence numbers,
   quiesce fence, in-flight counter, optional snowflake generator).
   Optional opts map: :logger, :sinks (vector of sink fns), :config
   (builds sinks from config), :snowflake-generator (BD-2b event-id
   generator for lexically-sortable ids)."
  stream/create-event-stream)
(def create-envelope
  "Build an event envelope map with an atomically-assigned per-workflow
   sequence number. Returns a map carrying :event/type, :event/id (a
   uuid? — snowflake-encoded when the stream has a generator, else
   random), :event/timestamp, :event/version, :event/sequence-number,
   :workflow/id, :message, plus any identity fields from the opts arity
   (:org/id, :workspace/id, :repo/id, :auth/context, :event/parent-id,
   :agent/id, :agent/instance-id)."
  stream/create-envelope)
(def publish!
  "Publish an event to the stream: fan out to sinks, append to the
   in-memory log, fan out to matching subscribers, log. Returns the
   published event map. If the event's workflow has been quiesced
   (BD-2a), short-circuits and returns a {:rejected? true :reason
   :workflow-quiesced :workflow-id ... :event-type ...} map without
   running sinks or subscribers."
  stream/publish!)
(def subscribe!
  "Register a callback for events. 3-arg form subscribes to all events;
   4-arg form takes a filter-fn (fn [event] -> bool) so only matching
   events are delivered. Returns the subscriber-id passed in."
  stream/subscribe!)
(def unsubscribe!
  "Remove a subscriber (and its filter) by id. Returns nil."
  stream/unsubscribe!)
(def get-events
  "Query the in-memory event log. Returns a vector of event maps.
   Optional opts map filters/pages: :workflow-id, :event-type, :offset,
   :limit."
  stream/get-events)
(def get-latest-status
  "Return the most recent :agent/status event map for a workflow (and
   optionally a specific agent-id), or nil when none exist."
  stream/get-latest-status)

;; BD-2b: canonical on-disk paths. Exposed so callers outside this
;; component (cli workflow runner, sub-3 cleanup) agree with the file
;; sink on where a workflow's events + manifest live.
(def workflow-dir
  "Return the per-workflow events directory as a java.io.File. As of
   BD-2b sub-3b this is `{base-dir}/live/{workflow-id}/` (an alias for
   the live-workflow-dir). The file sink and the manifest module both
   reference this path so archival's live → archived rename moves them
   together. Arities: (workflow-id) defaults base-dir to
   ~/.miniforge/events; (base-dir workflow-id)."
  sinks/workflow-dir)

(def serialize-event
  "Serialize an event map to the canonical Transit-JSON wire string —
   the exact encoding the file sink writes to disk (verbose mode: no
   cache codes; UUIDs/instants as `~u...`/`~t...` tag-strings the Rust
   consumer strips directly). Exposed for contract tooling: the
   supervisory golden-fixture generator serializes through this fn so
   vendored fixtures cannot drift from the production byte path.
   Pure: no IO."
  sinks/event->transit-json)

(def digest-content
  "Produce a bounded, reproducible digest map from arbitrary content
   (string, byte array, or any value coerced via str). Returns
   {:digest/preview (Unicode-safe UTF-8 prefix capped at
   max-preview-bytes), :digest/sha256 (lowercase hex SHA-256),
   :digest/original-size (UTF-8 byte length)}. Pure: no IO."
  digest/digest-content)

;; BD-2a shutdown-ordering primitives.
(def quiesce!
  "Fence future publishes for a workflow (when :workflow-id is given)
   and wait for in-flight publishes to settle. After return, publish!
   for that workflow returns a rejection map. Without :workflow-id, adds
   no fence and only waits for in-flight publishes. Returns a map:
   {:ok? true :pending-publishers 0} or {:ok? false :reason :timeout
   :pending-publishers N}. Opts: :workflow-id, :timeout-ms (default
   5000)."
  stream/quiesce!)
(def drain!
  "Wait for every event published before this call to reach all sinks,
   including each sink's optional drain hook. Returns a map: {:ok? true
   :drained-count N}, {:ok? false :reason :timeout :pending-count N}, or
   {:ok? false :reason :sink-error :failed-sinks [...]}. Opts:
   :timeout-ms (default 5000) is the total budget across in-flight
   settle plus sink drain."
  stream/drain!)

;; Phase heartbeat scheduler — start/stop liveness signalling for long-running phases.
(def start-heartbeat!
  "Start periodic :workflow/phase-heartbeat emission for a phase on a
   daemon scheduled executor. Returns an opaque stop handle map
   (:heartbeat/executor, :heartbeat/future, :heartbeat/phase-id,
   :heartbeat/last-tick, :heartbeat/seq-num) to pass to stop-heartbeat!.
   Throws ex-info when event-stream is not IDeref, or when :interval-ms
   is not a positive integer. opts: {:interval-ms long} (default 30s)."
  heartbeat/start-heartbeat!)

(def stop-heartbeat!
  "Stop a heartbeat scheduler returned by start-heartbeat!. Nil-safe.
   Cancels the periodic ScheduledFuture (does not interrupt an in-flight
   tick unless opts :may-interrupt? is true) then shuts down the backing
   executor. Returns nil."
  heartbeat/stop-heartbeat!)

;------------------------------------------------------------------------------ Layer 1
;; Event constructors

(def workflow-started
  "Build and return a :workflow/started event envelope map. Multi-arity
   for the legacy 2/3-arg call shape; the opts arity may carry
   :routing/trigger-event-id so the automation-edge-correlator links the
   handler workflow back to its routing trigger."
  events/workflow-started)
(def phase-started
  "Build and return a :workflow/phase-started event envelope map for the
   given phase keyword; optional context is attached as :phase/context."
  events/phase-started)
(def phase-completed
  "Build and return a :workflow/phase-completed event envelope map.
   Records :phase/outcome (default :success) and, when present on the
   result, duration, artifacts, error, transition-request, redirect-to,
   tokens, cost, meta, and termination-reason."
  events/phase-completed)
(def workspace-persisted
  "Build and return a :workspace/persisted event envelope map recording
   that a phase's worktree was archived to a checkpoint (phase, env-id,
   branch, commit-sha, bundle-path, persist-tier)."
  events/workspace-persisted)
(def agent-chunk
  "Build and return an :agent/chunk event envelope map carrying a
   streamed text delta for an agent; sets :chunk/done? when the optional
   done? flag is truthy."
  events/agent-chunk)
(def agent-status
  "Build and return an :agent/status event envelope map carrying a
   status-type keyword and human message for an agent."
  events/agent-status)
(def agent-tool-call
  "Build and return an :agent/tool-call event envelope map carrying the
   tool name(s) an agent invoked (:tool/name, :tool/names, :tool/call-id,
   :tool/args-preview). Legacy event kept for existing consumers."
  events/agent-tool-call)
(def agent-tool-call-started
  "Build and return an :agent/tool-call-started event envelope map
   marking the start of a tool call (:tool/name, :tool/names,
   :tool/args-digest, :tool/call-id); opens the latency span closed by
   tool-call-completed."
  events/agent-tool-call-started)
(def tool-call-completed
  "Build and return a :tool/call-completed event envelope map closing
   the latency span opened by agent-tool-call-started (:tool/call-id,
   :tool/result-digest, :tool/duration-ms, :tool/success?, :tool/error)."
  events/tool-call-completed)
(def phase-heartbeat
  "Build and return a :workflow/phase-heartbeat event envelope map for
   long-running phase liveness (:phase/active-since,
   :phase/events-emitted, :phase/last-event-at,
   :phase/gap-since-last-event-ms)."
  events/phase-heartbeat)
(def workflow-completed
  "Build and return a :workflow/completed event envelope map carrying a
   status keyword and optional duration plus opts (tokens, cost,
   pr-info, pr-infos, evidence-bundle-id)."
  events/workflow-completed)
(def workflow-failed
  "Build and return a :workflow/failed event envelope map. Normalizes
   the error (Throwable, anomaly map, or plain map) into
   :workflow/failure-reason, :workflow/error-details,
   :workflow/anomaly-code, and :workflow/retryable?; optional
   :failure/class."
  events/workflow-failed)
(def llm-request
  "Build and return an :llm/request event envelope map for an LLM call
   (model, optional prompt-tokens); assigns a fresh :llm/request-id."
  events/llm-request)
(def llm-response
  "Build and return an :llm/response event envelope map for an LLM
   response correlated by request-id; optional metrics add
   completion/total tokens, duration, and cost."
  events/llm-response)
(def agent-started
  "Build and return an :agent/started event envelope map; optional
   context is attached as :agent/context."
  events/agent-started)
(def agent-completed
  "Build and return an :agent/completed event envelope map; optional
   result is attached as :agent/result."
  events/agent-completed)
(def agent-failed
  "Build and return an :agent/failed event envelope map; optional error
   becomes :agent/error and optional :failure/class is carried through."
  events/agent-failed)
(def gate-started
  "Build and return a :gate/started event envelope map; optional
   artifact-summary is attached as :gate/artifact-summary."
  events/gate-started)
(def gate-passed
  "Build and return a :gate/passed event envelope map; optional
   duration-ms is attached as :gate/duration-ms."
  events/gate-passed)
(def gate-failed
  "Build and return a :gate/failed event envelope map; optional
   violations become :gate/violations and optional :failure/class is
   carried through."
  events/gate-failed)
(def gate-rule-applied
  "Build and return a :gate/rule-applied event envelope map: per-rule
   policy evidence recording rule-id evaluated in phase with status
   (:passed, :failed, :skipped-by-phase, :not-applicable). Optional
   extra carries :severity, :enforcement, and a NON-SENSITIVE
   :violation summary."
  events/gate-rule-applied)
(def tool-invoked
  "Build and return a :tool/invoked event envelope map for a tool an
   agent invoked; optional params-summary is attached as
   :tool/params-summary."
  events/tool-invoked)
(def tool-completed
  "Build and return a :tool/completed event envelope map; optional
   result-summary is attached as :tool/result-summary."
  events/tool-completed)
(def milestone-reached
  "Build and return a :workflow/milestone-reached event envelope map for
   a milestone-id; optional description overrides the default message."
  events/milestone-reached)
(def milestone-started
  "Build and return a :phase/milestone-started event envelope map for a
   milestone-id; optional description overrides the default message."
  events/milestone-started)
(def milestone-completed
  "Build and return a :phase/milestone-completed event envelope map for
   a milestone-id; optional description overrides the default message."
  events/milestone-completed)
(def milestone-failed
  "Build and return a :phase/milestone-failed event envelope map for a
   milestone-id; optional reason overrides the default message."
  events/milestone-failed)
(def task-state-changed
  "Build and return a :task/state-changed event envelope map recording a
   DAG task transitioning from-state to-state (:dag/id, :task/id,
   :task/from-state, :task/to-state); optional context as :task/context."
  events/task-state-changed)
(def task-frontier-entered
  "Build and return a :task/frontier-entered event envelope map for a
   DAG task entering the ready frontier; optional frontier-size as
   :task/frontier-size."
  events/task-frontier-entered)
(def task-skip-propagated
  "Build and return a :task/skip-propagated event envelope map for a DAG
   task skipped by upstream propagation; optional cause-task as
   :task/cause-task."
  events/task-skip-propagated)
(def inter-agent-message-sent
  "Build and return an :agent/message-sent event envelope map for a
   message from one agent to another (:from-agent/id, :to-agent/id);
   optional message-type as :message/type."
  events/inter-agent-message-sent)
(def inter-agent-message-received
  "Build and return an :agent/message-received event envelope map for a
   message received by an agent (:from-agent/id, :to-agent/id); optional
   message-type as :message/type."
  events/inter-agent-message-received)
(def listener-attached
  "Build and return a :listener/attached event envelope map for a
   listener id; optional listener-type and capability as :listener/type
   / :listener/capability."
  events/listener-attached)
(def listener-detached
  "Build and return a :listener/detached event envelope map for a
   listener id; optional reason as :listener/reason."
  events/listener-detached)
(def annotation-created
  "Build and return an :annotation/created event envelope map for an
   advisory annotation from a listener (:listener/id,
   :annotation/type); optional content as :annotation/content."
  events/annotation-created)
(def control-action-requested
  "Build and return a :control-action/requested event envelope map
   (:action/id, :action/type); optional requester as :action/requester."
  events/control-action-requested)
(def control-action-executed
  "Build and return a :control-action/executed event envelope map for an
   action id; optional result as :action/result."
  events/control-action-executed)
(def chain-started
  "Build and return a :chain/started event envelope map (chains are not
   workflow-scoped, so :workflow/id is nil) carrying :chain/id and
   :chain/step-count."
  events/chain-started)
(def chain-step-started
  "Build and return a :chain/step-started event envelope map carrying
   :chain/id, :step/id, :step/index, and :step/workflow-id."
  events/chain-step-started)
(def chain-step-completed
  "Build and return a :chain/step-completed event envelope map carrying
   :chain/id, :step/id, and :step/index."
  events/chain-step-completed)
(def chain-step-failed
  "Build and return a :chain/step-failed event envelope map carrying
   :chain/id, :step/id, :step/index, and :chain/error; optional
   :failure/class."
  events/chain-step-failed)
(def chain-completed
  "Build and return a :chain/completed event envelope map carrying
   :chain/id, :chain/duration-ms, and :chain/step-count."
  events/chain-completed)
(def chain-failed
  "Build and return a :chain/failed event envelope map carrying
   :chain/id, :chain/failed-step, and :chain/error; optional
   :failure/class."
  events/chain-failed)

;; OCI container events
(def container-started
  "Build and return an :oci/container-started event envelope map for a
   container id; optional opts add :oci/image-digest and
   :oci/trust-level."
  events/container-started)
(def container-completed
  "Build and return an :oci/container-completed event envelope map
   carrying :oci/container-id and :oci/exit-code; optional duration-ms
   as :oci/duration-ms."
  events/container-completed)

;; Tool supervision events
(def tool-use-evaluated
  "Build and return a :supervision/tool-use-evaluated event envelope map
   capturing a supervisor's decision on a tool-use request (:tool/name,
   :supervision/decision); optional opts add :supervision/reasoning,
   :supervision/meta-eval?, :supervision/confidence, :workflow/phase."
  events/tool-use-evaluated)

;; Meta-loop supervision events
(def meta-loop-halt-requested
  "Build and return a :meta-loop/halt-requested event envelope map recording a
   meta-agent's REFUSE: :halt/halting-agent and :halt/reason-code (RefusalReason);
   optional opts add :halt/detail and :workflow/phase."
  events/meta-loop-halt-requested)

;; Control plane events
(def cp-agent-registered
  "Build and return a :control-plane/agent-registered event envelope map
   for an external agent registering with the control plane (:cp/agent-id,
   :cp/vendor); optional opts add name, external-id, capabilities,
   metadata, tags, heartbeat-interval-ms."
  events/cp-agent-registered)
(def cp-agent-heartbeat
  "Build and return a :control-plane/agent-heartbeat event envelope map
   (:cp/agent-id, :cp/status); optional opts add :cp/task and
   :cp/metrics."
  events/cp-agent-heartbeat)
(def cp-agent-state-changed
  "Build and return a :control-plane/agent-state-changed event envelope
   map recording an agent's normalized state transition (:cp/agent-id,
   :cp/from-status, :cp/to-status)."
  events/cp-agent-state-changed)
(def cp-decision-created
  "Build and return a :control-plane/decision-created event envelope map
   for an agent's decision request (:cp/agent-id, :cp/decision-id,
   :cp/summary); optional priority-or-opts add priority, type, context,
   options, deadline."
  events/cp-decision-created)
(def cp-decision-resolved
  "Build and return a :control-plane/decision-resolved event envelope
   map for a human-resolved decision (:cp/decision-id, :cp/resolution);
   optional comment as :cp/comment."
  events/cp-decision-resolved)
(def intervention-requested
  "Build and return a :supervisory/intervention-requested event envelope
   map, merging the intervention map into the envelope."
  events/intervention-requested)
(def intervention-state-changed
  "Build and return a :supervisory/intervention-state-changed event
   envelope map for an InterventionRequest lifecycle change
   (:intervention/id, :intervention/state); optional opts carry the
   many :intervention/* detail fields (from-state, type, target-type,
   target-id, requested-by, justification, outcome, ...)."
  events/intervention-state-changed)

;; PR scoring (N5-delta-2 §4.1)
(def pr-created
  "Build and return a :pr/created event envelope map for a
   workflow-owned PR (:pr/repo, :pr/number, :pr/url, :pr/branch);
   optional title, author, merge-order. Lets consumers attach a PR to
   its owning workflow run."
  events/pr-created)
(def pr-scored
  "Build and return a :pr/scored event envelope map carrying PR scoring
   results (:pr/readiness, :pr/risk, :pr/policy, :pr/recommendation —
   any subset). Not inherently workflow-scoped; opts :workflow/id scopes
   it to a run."
  events/pr-scored)

;; Routing trigger events (N5-delta-4 §4.2)
(def pr-monitor-review-comments-arrived
  "Build and return a :pr-monitor/review-comments-arrived event envelope
   map (:workflow/id nil — PR-scoped) for new review comments on an owned
   PR (:pr/repo, :pr/number, :comments/count); optional agent-session-id
   as :comments/agent-session-id."
  events/pr-monitor-review-comments-arrived)
(def pr-monitor-ci-failed
  "Build and return a :pr-monitor/ci-failed event envelope map
   (:workflow/id nil) for a CI status reaching a non-success terminal
   state on an owned PR (:pr/repo, :pr/number, :ci/check-name,
   :ci/conclusion). :ci/conclusion is an open keyword."
  events/pr-monitor-ci-failed)
(def standards-review-posted
  "Build and return a :standards-review/posted event envelope map
   (:workflow/id nil) for a standards-review comment on a PR (:pr/repo,
   :pr/number, :review/severity). :review/severity is an open keyword;
   optional affected-workflow-run-id as :affected/workflow-run-id."
  events/standards-review-posted)

;; Zettelkasten lifecycle (miniforge-fleet Phase E.3)
(def zettel-promoted
  "Build and return a :zettel/promoted event envelope map emitted when a
   zettel revision transitions to :trusted and becomes eligible to ride
   the Fleet event log. Carries the zettel's revision-keyed identity and
   content; Fleet-share intent (:fleet/shareable, :fleet/share-scope,
   :privacy/classification) rides through from the zettel when present.
   Args: stream, workflow-id, zettel map, oss-version; optional opts
   carry envelope identity (:org/id, :workspace/id, :repo/id,
   :auth/context)."
  events/zettel-promoted)

;------------------------------------------------------------------------------ Layer 2
;; Listener, control, approval, and callback APIs

(def register-listener!
  "Register a listener on the stream with a capability level (:observe,
   :advise, or :control), wrapping its callback with capability-based
   and user filters and emitting :listener/attached. Returns the new
   listener-id (UUID). Throws an :anomalies/incorrect anomaly when the
   capability is invalid. listener-spec keys: :listener/type,
   :listener/capability, :listener/identity, :listener/filters,
   :listener/callback, :listener/options."
  listeners/register-listener!)
(def deregister-listener!
  "Unsubscribe a listener by id, drop its metadata, and emit
   :listener/detached. Returns true when the listener existed, or nil
   when no listener matched the id."
  listeners/deregister-listener!)
(def list-listeners
  "Return a sequence of registered listener metadata maps for the
   stream (empty when none registered)."
  listeners/list-listeners)
(def submit-annotation!
  "Submit an advisory annotation from a listener; requires :advise or
   :control capability. Emits and returns the :annotation/created event
   map. Throws an :anomalies/not-found anomaly when the listener is
   unknown, or :anomalies/forbidden when its capability is
   insufficient. annotation keys: :annotation/type, :annotation/content,
   :annotation/workflow-id."
  listeners/submit-annotation!)

(def create-control-action
  "Build a structured control-action map from an action-type keyword
   (:pause, :resume, :retry, :rollback, :cancel, :quarantine,
   :adjust-budget, :emergency-stop, :gate-override, ...), a target map
   ({:target-type :workflow|:agent|:fleet :target-id ...}), and a
   requester map ({:principal :role :listener-id}). Returns a map with
   :action/id, :action/type, :action/target, :action/requester,
   :action/status :pending, :action/created-at, plus optional
   :action/justification and :action/parameters."
  control/create-control-action)
(def default-roles
  "Default RBAC role definitions for structured control actions."
  control/default-roles)
(def authorize-action
  "Check RBAC authorization for a control action against role
   definitions. Returns {:authorized? true :reason string} when the
   role permits the action on the target category, else {:authorized?
   false :reason string :anomaly map} (unknown role / unknown target
   type / forbidden action)."
  control/authorize-action)
(def execute-control-action!
  "Authorize then execute a control action. Emits
   :control-action/requested before and :control-action/executed after.
   On RBAC denial returns {:status :denied :reason string :anomaly map}
   and runs no execution-fn. On authorization, runs execution-fn and
   returns its result wrapped via response/success, or
   response/failure on a thrown exception."
  control/execute-control-action!)
(def requires-approval?
  "Return true when the given action-type keyword requires multi-party
   approval (:gate-override or :budget-escalation), else false."
  control/requires-approval?)
(def execute-control-action-with-approval!
  "Execute a control action, gating on approval first. When the action
   type requires approval, creates an approval request, emits
   :approval/requested, and returns {:status :awaiting-approval
   :approval/id ... :approval/required-signers ... :approval/quorum
   ...}. Otherwise delegates to execute-control-action! and returns its
   result map. approval-opts: :required-signers, :quorum,
   :approval-manager."
  control/execute-control-action-with-approval!)

(def approval-succeeded?
  "Return true when a builder response (from response/success)
   succeeded, else false. Use to test submit-approval / cancel-approval
   results."
  approval/approval-succeeded?)
(def approval-failed?
  "Return true when a builder response (from response/failure) failed,
   else false. Use to test submit-approval / cancel-approval results."
  approval/approval-failed?)
(def create-approval-request
  "Create a new approval-request map requiring quorum-based signing.
   Args: action-id (UUID), required-signers (vector of strings), quorum
   (count), and optional opts (:expires-in-hours default 24,
   :metadata). Returns a map with :approval/id, :approval/action-id,
   :approval/status :pending, :approval/required-signers,
   :approval/quorum, :approval/signatures [], :approval/created-at,
   :approval/expires-at, plus :approval/metadata when supplied."
  approval/create-approval-request)
(def submit-approval
  "Submit an :approve or :reject decision from a signer for an approval
   request. On success returns a builder success wrapping the updated
   request (status transitions to :approved on quorum, :rejected
   immediately on any reject, else stays :pending). Returns a builder
   failure (anomaly) when not pending, expired, signer unauthorized,
   already signed, or the decision is invalid. Optional opts: :reason."
  approval/submit-approval)
(def check-approval-status
  "Return the effective status keyword of an approval request, mapping
   a pending-but-expired request to :expired. One of :pending,
   :approved, :rejected, :expired, or :cancelled."
  approval/check-approval-status)
(def cancel-approval
  "Cancel a pending approval request, recording canceller and reason.
   Returns a builder success wrapping the updated request (status
   :cancelled), or a builder failure when the request is not pending."
  approval/cancel-approval)
(def create-approval-manager
  "Create an atom-backed approval manager. Returns an atom holding
   {:approvals {}} keyed by approval id."
  approval/create-approval-manager)
(def store-approval!
  "Store an approval request in the manager atom under its :approval/id.
   Returns the stored approval map."
  approval/store-approval!)
(def get-approval
  "Look up an approval request in the manager atom by id. Returns the
   approval map, or nil when absent."
  approval/get-approval)
(def update-approval!
  "Replace an approval request in the manager atom (keyed by its
   :approval/id). Returns the updated approval map."
  approval/update-approval!)
(def list-approvals
  "List approval requests held by the manager atom. Returns a vector of
   approval maps. Optional opts :status filters by effective status
   (via check-approval-status)."
  approval/list-approvals)

(def create-streaming-callback
  "Callback invoked by the LLM client for each parsed stream event.
   Returns a stateful callback fn of one parsed-event map. On tool-use
   events emits both :agent/tool-call (legacy) and
   :agent/tool-call-started (opens a latency span, digesting
   tool-args-preview via digest-content); on tool-result events emits
   :tool/call-completed closing that span with :tool/duration-ms and a
   digested :tool/result-digest; on heartbeat does nothing; otherwise
   emits :agent/chunk for the text delta. Args: stream-atom,
   workflow-id, agent-id, optional opts (:print?, :quiet?)."
  callbacks/create-streaming-callback)

;------------------------------------------------------------------------------ Layer 2
;; Reliability metric event constructors (N3 §3.17)

(def sli-computed
  "Build and return a :reliability/sli-computed event envelope map
   (:workflow/id nil) for an SLI value computed over a rolling window
   (:sli/name, :sli/value, :sli/window); optional opts add :sli/tier and
   :sli/dimensions."
  events/sli-computed)
(def slo-breach
  "Build and return a :reliability/slo-breach event envelope map
   (:workflow/id nil) for a missed SLO target (:slo/sli-name,
   :slo/target, :slo/actual, :slo/tier, :slo/window)."
  events/slo-breach)
(def error-budget-update
  "Build and return a :reliability/error-budget-update event envelope
   map (:workflow/id nil) for recomputed error budget state
   (:budget/tier, :budget/sli, :budget/remaining, :budget/burn-rate,
   :budget/window)."
  events/error-budget-update)
(def dependency-health-updated
  "Build and return a :dependency/health-updated event envelope map
   (:workflow/id nil) for a changed dependency health projection,
   merging the dependency map; optional previous-status as
   :dependency/previous-status."
  events/dependency-health-updated)
(def dependency-recovered
  "Build and return a :dependency/recovered event envelope map
   (:workflow/id nil) for a dependency returning to healthy, merging the
   dependency map; optional previous-status as
   :dependency/previous-status."
  events/dependency-recovered)
(def degradation-mode-changed
  "Build and return a :reliability/degradation-mode-changed event
   envelope map (:workflow/id nil) for a degradation-mode transition
   (:degradation/from, :degradation/to, :degradation/trigger)."
  events/degradation-mode-changed)
(def safe-mode-entered
  "Build and return a :safe-mode/entered event envelope map (:workflow/id
   nil) recording safe-mode activation (:safe-mode/trigger); optional
   details as :safe-mode/trigger-details."
  events/safe-mode-entered)
(def safe-mode-exited
  "Build and return a :safe-mode/exited event envelope map (:workflow/id
   nil) recording safe-mode deactivation (:safe-mode/exited-by,
   :safe-mode/justification, :safe-mode/duration-ms,
   :safe-mode/workflows-queued)."
  events/safe-mode-exited)

;; Repository intelligence event constructors (RN-19/20)
(def repo-index-quality-measured
  "Build and return a :repo-index/quality-measured event envelope map
   (:workflow/id nil) emitted by the index quality tracker (RN-19) when
   it samples the composite quality score for a named index
   (:index/id, :index/quality-score, :index/coverage, :index/staleness-ms);
   optional opts add :index/measured-at."
  events/repo-index-quality-measured)

(def repo-index-coverage-changed
  "Build and return a :repo-index/coverage-changed event envelope map
   (:workflow/id nil) emitted by the index quality tracker (RN-20) when
   coverage for a named index changes beyond the configured threshold
   (:index/id, :index/previous-coverage, :index/coverage); optional opts
   add :index/changed-files."
  events/repo-index-coverage-changed)

;------------------------------------------------------------------------------ Layer 2
;; Meta-loop event constructors

(def meta-loop-cycle-completed
  "Build and return a :meta-loop/cycle-completed event envelope map
   (:workflow/id nil), merging the summary map (signals, diagnoses,
   proposals counts)."
  events/meta-loop-cycle-completed)
(def meta-loop-cycle-failed
  "Build and return a :meta-loop/cycle-failed event envelope map
   (:workflow/id nil) for an unhandled meta-loop exception
   (:meta-loop/error, :meta-loop/error-class)."
  events/meta-loop-cycle-failed)

;------------------------------------------------------------------------------ Layer 3
;; Observer / knowledge failure event constructors

(def observer-signal-failed
  "Build and return an :observer/signal-failed event envelope map for a
   failure forwarding a signal to the meta-loop (:observer/error)."
  events/observer-signal-failed)
(def knowledge-synthesis-failed
  "Build and return a :knowledge/synthesis-failed event envelope map
   (:workflow/id nil) for a pattern-synthesis failure (:knowledge/error)."
  events/knowledge-synthesis-failed)
(def knowledge-promotion-failed
  "Build and return a :knowledge/promotion-failed event envelope map
   (:workflow/id nil) for a learning-promotion failure
   (:knowledge/error)."
  events/knowledge-promotion-failed)

;; Agent stream-stall and session events (GROUP 1+4, GROUP 2)
(def agent-stream-stalled
  "Build and return an :agent/stream-stalled event envelope map
   indicating the agent output stream has gone silent past the gap
   threshold (:workflow/phase, :stream/gap-duration-ms, :agent/backend).
   Consumed by the self-healing supervisor."
  events/agent-stream-stalled)
(def agent-session-captured
  "Build and return an :agent/session-captured event envelope map
   recording the backend session id from the initial handshake
   (:workflow/phase, :agent/backend, :agent/session-id). Must be emitted
   before the first tool call so resume-on-kill has a valid session id."
  events/agent-session-captured)

;------------------------------------------------------------------------------ Layer 4
;; Event-stream reader — replay events from the file-sink layout

(def strip-transit-prefix
  "Turn a raw transit-JSON parse back into idiomatic Clojure data,
   walking maps and vectors recursively: strings prefixed with `~:`
   become keywords; `~u` / `~t` (UUID / instant) prefixes become the
   stripped string form. Returns the de-prefixed value."
  reader/strip-transit-prefix)

(def read-workflow-events
  "Read every `.json` event file under a workflow directory, sorted by
   timestamp-prefixed filename, parsed as transit-JSON and de-prefixed.
   Arg `dir` is a java.io.File or String path. Returns a vector of
   parsed event maps, or nil when the directory does not exist.
   Unparseable files are silently dropped."
  reader/read-workflow-events)

(def read-workflow-events-by-id
  "Read events for a workflow id under a base events dir, probing the
   archived → live → legacy layouts and reading the first that exists.
   Args: base-dir, workflow-id (UUID, keyword, or string). Returns a
   vector of parsed event maps, or nil when no layout contains the
   workflow."
  reader/read-workflow-events-by-id)

;------------------------------------------------------------------------------ Layer 5
;; Event timeline renderer

(def render-timeline
  "Transform a seq of parsed event maps (as from read-workflow-events)
   into a human-readable multi-line timeline string, one line per event
   (HH:mm:ss  <phase>  <detail>) with inserted gap lines between events
   whose timestamp gap exceeds the threshold. Returns a string (empty
   string for empty/nil input). Pure: no IO. Arities: (events) uses a
   60s default gap; (events opts) takes {:gap-threshold-ms long}."
  timeline/render-timeline)
