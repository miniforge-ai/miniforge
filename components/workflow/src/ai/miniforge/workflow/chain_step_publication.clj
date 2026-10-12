;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-step-publication
  "Publish a child terminal outcome, retaining publication failure provenance."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail! [opts chain-id step index error]
  (publication/emit-step! opts events/chain-step-failed chain-id (:step/id step) index error))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} complete! [opts chain-id step index]
  (let [receipt (publication/emit-step! opts events/chain-step-completed chain-id (:step/id step) index)]
    (when (outcome/failure-value? receipt)
      (fail! opts chain-id step index (messages/t :chain/step-completion-publication-failed)))
    receipt))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} terminal! [opts chain-id step index result]
  (if (outcome/completed? result)
    (complete! opts chain-id step index)
    (fail! opts chain-id step index (:execution/error result))))

(comment
  ::child-terminal-publication)
