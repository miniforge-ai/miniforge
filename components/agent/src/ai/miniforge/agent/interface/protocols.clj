(ns ai.miniforge.agent.interface.protocols
  "Protocol and configuration re-exports for the agent public API."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.interface.protocols.memory :as mem-proto]
   [ai.miniforge.agent.interface.protocols.messaging :as msg-proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol and configuration re-exports

(def Agent agent-proto/Agent)
(def AgentLifecycle agent-proto/AgentLifecycle)
(def AgentExecutor agent-proto/AgentExecutor)
(def LLMBackend agent-proto/LLMBackend)
(def Memory mem-proto/Memory)
(def MemoryStore mem-proto/MemoryStore)
(def InterAgentMessaging msg-proto/InterAgentMessaging)
(def MessageRouter msg-proto/MessageRouter)
(def MessageValidator msg-proto/MessageValidator)

(def default-role-configs core/default-role-configs)
(def role-capabilities core/role-capabilities)
(def role-system-prompts core/role-system-prompts)
