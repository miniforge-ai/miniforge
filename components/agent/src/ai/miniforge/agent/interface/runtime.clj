(ns ai.miniforge.agent.interface.runtime
  "Agent creation, execution, and lifecycle operations."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]))

;------------------------------------------------------------------------------ Layer 0
;; Agent creation and execution

(def create-agent core/create-agent)
(def create-agent-map core/create-agent-map)
(def create-executor core/create-executor)

(defn execute
  [executor agent task context]
  (agent-proto/execute executor agent task context))

(defn invoke
  [agent task context]
  (agent-proto/invoke agent task context))

(defn validate
  [agent output context]
  (agent-proto/validate agent output context))

(defn repair
  [agent output errors context]
  (agent-proto/repair agent output errors context))

(defn init
  [agent config]
  (agent-proto/init agent config))

(defn agent-status
  [agent]
  (agent-proto/status agent))

(defn shutdown
  [agent]
  (agent-proto/shutdown agent))
