(ns ai.miniforge.agent.interface.llm
  "LLM-facing utility helpers for the agent public API."
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.protocols.impl.memory :as mem-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Utility helpers

(def estimate-tokens mem-impl/estimate-tokens)
(def estimate-cost core/estimate-cost)
(def create-mock-llm core/create-mock-llm)
