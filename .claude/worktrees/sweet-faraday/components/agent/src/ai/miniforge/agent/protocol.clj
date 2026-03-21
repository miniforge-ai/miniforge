(ns ai.miniforge.agent.protocol
  "Agent protocol definitions.
   Layer 0: Core protocols for agent execution and lifecycle management.

   Note: The protocols have been moved to:
   - ai.miniforge.agent.interface.protocols.agent (Agent, AgentLifecycle, AgentExecutor, LLMBackend)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.agent.interface.protocols.agent :as p]))

;; Re-export protocols for backward compatibility
(def Agent p/Agent)
(def AgentLifecycle p/AgentLifecycle)
(def AgentExecutor p/AgentExecutor)
(def LLMBackend p/LLMBackend)

;; Re-export protocol methods
(def invoke p/invoke)
(def validate p/validate)
(def repair p/repair)
(def init p/init)
(def status p/status)
(def shutdown p/shutdown)
(def abort p/abort)
(def execute p/execute)
(def complete p/complete)
