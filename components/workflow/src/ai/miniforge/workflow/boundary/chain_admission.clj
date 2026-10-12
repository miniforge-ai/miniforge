;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-admission
  "Admit a resolved composition before any execution or identity allocation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.boundary.chain-retention :as retention]
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

(defn- ^{:stratum 0} retain! [admitted opts]
  (if (contains? opts :chain/retention-directory)
    (anomaly/let-ok [_receipt (retention/persist-admitted! (:chain/retention-directory opts)
                                                         (:chain/run-record admitted))]
      admitted)
    admitted))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-chain [definition input opts]
  (anomaly/let-ok [admitted (prepare definition)
                   retained (retain! admitted opts)]
    (chain/run-chain retained input opts)))

(comment
  ::resolved-chain-admission)
