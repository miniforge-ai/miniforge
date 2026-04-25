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
   [ai.miniforge.event-stream.reader :as reader]))

;------------------------------------------------------------------------------ Layer 0
;; Stream lifecycle and control state

(def create-control-state control-state/create-control-state)
(def pause! control-state/pause!)
(def resume! control-state/resume!)
(def cancel! control-state/cancel!)
(def paused? control-state/paused?)
(def cancelled? control-state/cancelled?)

(def create-event-stream stream/create-event-stream)
(def create-envelope stream/create-envelope)
(def publish! stream/publish!)
(def subscribe! stream/subscribe!)
(def unsubscribe! stream/unsubscribe!)
(def get-events stream/get-events)
(def get-latest-status stream/get-latest-status)

;------------------------------------------------------------------------------ Layer 1
;; Event constructors

(def workflow-started events/workflow-started)
(def phase-started events/phase-started)
(def phase-completed events/phase-completed)
(def agent-chunk events/agent-chunk)
(def agent-status events/agent-status)
(def agent-tool-call events/agent-tool-call)
(def workflow-completed events/workflow-completed)
(def workflow-failed events/workflow-failed)
(def llm-request events/llm-request)
(def llm-response events/llm-response)
(def agent-started events/agent-started)
(def agent-completed events/agent-completed)
(def agent-failed events/agent-failed)
(def gate-started events/gate-started)
(def gate-passed events/gate-passed)
(def gate-failed events/gate-failed)
(def tool-invoked events/tool-invoked)
(def tool-completed events/tool-completed)
(def milestone-reached events/milestone-reached)
(def milestone-started events/milestone-started)
(def milestone-completed events/milestone-completed)
(def milestone-failed events/milestone-failed)
(def task-state-changed events/task-state-changed)
(def task-frontier-entered events/task-frontier-entered)
(def task-skip-propagated events/task-skip-propagated)
(def inter-agent-message-sent events/inter-agent-message-sent)
(def inter-agent-message-received events/inter-agent-message-received)
(def listener-attached events/listener-attached)
(def listener-detached events/listener-detached)
(def annotation-created events/annotation-created)
(def control-action-requested events/control-action-requested)
(def control-action-executed events/control-action-executed)
(def chain-started events/chain-started)
(def chain-step-started events/chain-step-started)
(def chain-step-completed events/chain-step-completed)
(def chain-step-failed events/chain-step-failed)
(def chain-completed events/chain-completed)
(def chain-failed events/chain-failed)

;; OCI container events
(def container-started events/container-started)
(def container-completed events/container-completed)

;; Tool supervision events
(def tool-use-evaluated events/tool-use-evaluated)

;; Control plane events
(def cp-agent-registered events/cp-agent-registered)
(def cp-agent-heartbeat events/cp-agent-heartbeat)
(def cp-agent-state-changed events/cp-agent-state-changed)
(def cp-decision-created events/cp-decision-created)
(def cp-decision-resolved events/cp-decision-resolved)
(def intervention-requested events/intervention-requested)
(def intervention-state-changed events/intervention-state-changed)

;; PR scoring (N5-delta-2 §4.1)
(def pr-created events/pr-created)
(def pr-scored events/pr-scored)

;------------------------------------------------------------------------------ Layer 2
;; Listener, control, approval, and callback APIs

(def register-listener! listeners/register-listener!)
(def deregister-listener! listeners/deregister-listener!)
(def list-listeners listeners/list-listeners)
(def submit-annotation! listeners/submit-annotation!)

(def create-control-action control/create-control-action)
(def authorize-action control/authorize-action)
(def execute-control-action! control/execute-control-action!)
(def requires-approval? control/requires-approval?)
(def execute-control-action-with-approval! control/execute-control-action-with-approval!)

(def approval-succeeded? approval/approval-succeeded?)
(def approval-failed? approval/approval-failed?)
(def create-approval-request approval/create-approval-request)
(def submit-approval approval/submit-approval)
(def check-approval-status approval/check-approval-status)
(def cancel-approval approval/cancel-approval)
(def create-approval-manager approval/create-approval-manager)
(def store-approval! approval/store-approval!)
(def get-approval approval/get-approval)
(def update-approval! approval/update-approval!)
(def list-approvals approval/list-approvals)

(def create-streaming-callback callbacks/create-streaming-callback)

;------------------------------------------------------------------------------ Layer 2
;; Reliability metric event constructors (N3 §3.17)

(def sli-computed events/sli-computed)
(def slo-breach events/slo-breach)
(def error-budget-update events/error-budget-update)
(def degradation-mode-changed events/degradation-mode-changed)
(def safe-mode-entered events/safe-mode-entered)
(def safe-mode-exited events/safe-mode-exited)

;------------------------------------------------------------------------------ Layer 2
;; Meta-loop event constructors

(def meta-loop-cycle-completed events/meta-loop-cycle-completed)
(def meta-loop-cycle-failed events/meta-loop-cycle-failed)

;------------------------------------------------------------------------------ Layer 3
;; Observer / knowledge failure event constructors

(def observer-signal-failed events/observer-signal-failed)
(def knowledge-synthesis-failed events/knowledge-synthesis-failed)
(def knowledge-promotion-failed events/knowledge-promotion-failed)

;------------------------------------------------------------------------------ Layer 4
;; Event-stream reader — replay events from the file-sink layout

(def strip-transit-prefix reader/strip-transit-prefix)
(def read-workflow-events reader/read-workflow-events)
(def read-workflow-events-by-id reader/read-workflow-events-by-id)
