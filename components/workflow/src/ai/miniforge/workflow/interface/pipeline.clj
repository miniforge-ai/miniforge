(ns ai.miniforge.workflow.interface.pipeline
  "Interceptor-pipeline workflow APIs."
  (:require
   [ai.miniforge.workflow.runner :as runner]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline API

(def run-pipeline runner/run-pipeline)
(def build-pipeline runner/build-pipeline)
(def validate-pipeline runner/validate-pipeline)

(defn run-chain
  [chain-def chain-input opts]
  ((requiring-resolve 'ai.miniforge.workflow.chain/run-chain)
   chain-def chain-input opts))

(defn load-chain
  [chain-id version]
  ((requiring-resolve 'ai.miniforge.workflow.chain-loader/load-chain)
   chain-id version))

(defn list-chains
  []
  ((requiring-resolve 'ai.miniforge.workflow.chain-loader/list-chains)))
