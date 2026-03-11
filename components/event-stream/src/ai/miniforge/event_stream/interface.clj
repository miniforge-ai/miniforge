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
   [ai.miniforge.event-stream.interface.stream :as stream]))

;------------------------------------------------------------------------------ Layer 0
;; Stream lifecycle and control state

(def create-control-state control-state/create-control-state)
(def pause! control-state/pause!)
(def resume! control-state/resume!)
(def cancel! control-state/cancel!)
(def paused? control-state/paused?)
(def cancelled? control-state/cancelled?)

(def create-event-stream stream/create-event-stream)
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
