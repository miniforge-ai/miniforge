;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-publication
  "Current edge acknowledgments gate binding and child execution."
  (:require [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.workflow.chain-edge-record :as record]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.chain-publication :as publication]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} emit! [opts type edge fields]
  (publication/emit! opts type (merge (record/identity-fields edge) fields)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failed! [opts edge reason]
  (emit! opts :chain.edge/failed edge
         {:edge/failure-reason reason :failure/class (failure/classify {:error/message reason})}))

(defn ^{:stratum 1} started! [opts edge]
  (emit! opts :chain.edge/started edge (select-keys edge [:edge/bindings-count])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} completed! [opts edge]
  (let [receipt (emit! opts :chain.edge/completed edge (select-keys edge [:edge/duration-ms]))]
    (when (outcome/failure-value? receipt)
      (failed! opts edge (messages/t :chain/edge-completion-publication-failed)))
    receipt))

(comment
  ::edge-publication)
