(ns ai.miniforge.agent.interface.meta
  "Meta-agent orchestration API."
  (:require
   [ai.miniforge.agent.meta-coordinator :as meta-coord]
   [ai.miniforge.agent.meta.progress-monitor :as progress-monitor]))

;------------------------------------------------------------------------------ Layer 0
;; Meta-agent operations

(def create-progress-monitor-agent progress-monitor/create-progress-monitor-agent)
(def create-meta-coordinator meta-coord/create-coordinator)
(def check-all-meta-agents meta-coord/check-all-agents)
(def reset-all-meta-agents! meta-coord/reset-all-agents!)
(def get-meta-check-history meta-coord/get-check-history)
(def get-meta-agent-stats meta-coord/get-agent-stats)
