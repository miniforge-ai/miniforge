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
