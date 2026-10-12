;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-admission
  "Admit a resolved composition before any execution or identity allocation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-definition-spec :as spec]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} prepare [definition]
  (let [detached (composition/detach definition)]
    (if (schema/valid? spec/Definition detached)
      (assoc detached :chain/run-record (composition/run-record detached))
      (anomaly/anomaly :invalid-input (messages/t :chain/invalid-definition) {}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-chain [definition input opts]
  (let [admitted (prepare definition)]
    (if (anomaly/any-anomaly? admitted) admitted
        (chain/run-chain admitted input opts))))

(comment
  ::resolved-chain-admission)
