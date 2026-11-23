;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-event-legacy
  "Adapt semantic lifecycle fields to the unchanged legacy constructor contracts."
  (:require [ai.miniforge.event-stream.interface :as events]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private contracts
  {:chain/started [events/chain-started [:chain/definition-id :chain/step-count]]
   :chain/completed [events/chain-completed [:chain/definition-id :chain/duration-ms :chain/step-count]]
   :chain/failed [events/chain-failed [:chain/definition-id :chain/failed-step :chain/error]]
   :chain/step-started [events/chain-step-started [:chain/definition-id :step/id :step/index :step/workflow-id]]
   :chain/step-completed [events/chain-step-completed [:chain/definition-id :step/id :step/index]]
   :chain/step-failed [events/chain-step-failed [:chain/definition-id :step/id :step/index :chain/error]]})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} arguments [type payload]
  (let [[_constructor fields] (get contracts type)
        args (mapv payload fields)]
    (cond-> args
      (contains? payload :step/id) (conj (select-keys payload [:workflow/id])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} draft [stream type payload]
  (let [[constructor _fields] (get contracts type)]
    (apply constructor stream (arguments type payload))))

(comment
  (arguments :chain/started {}))
