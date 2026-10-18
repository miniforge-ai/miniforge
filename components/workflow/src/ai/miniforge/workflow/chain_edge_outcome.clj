;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-edge-outcome
  "Retain binding outcomes without confusing them with publication acknowledgments."
  (:require [ai.miniforge.clock.interface :as clock]
            [ai.miniforge.workflow.chain-edge-publication :as publication]
            [ai.miniforge.workflow.chain-edge-record :as record]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} publication-failure [edge error bindings]
  (assoc (outcome/failure error) :execution/edges [edge] :execution/bindings bindings
         :execution/emission-failure (outcome/with-child-identity error (:edge/to-workflow-id edge))))

(defn- ^{:stratum 0} failed-record [edge reason]
  (record/failed edge reason (java.util.Date. (clock/now-ms))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start-failed! [opts edge error]
  (let [reason (messages/t :chain/edge-start-publication-failed)
        failed (failed-record edge reason)]
    (publication/failed! opts failed reason)
    (publication-failure failed error [])))

(defn ^{:stratum 1} failed! [opts edge result]
  (let [reason (:execution/error result)
        failed (failed-record (record/with-binding-records edge (:execution/bindings result)) reason)
        receipt (publication/failed! opts failed reason)]
    (if (outcome/failure-value? receipt) (publication-failure failed receipt (:execution/bindings result))
        (assoc result :execution/edges [failed]))))

(defn ^{:stratum 1} completed! [opts edge report]
  (let [started (.getTime ^java.util.Date (:edge/started-at edge))
        completed (record/completed edge (:bindings report) (java.util.Date. (clock/now-ms))
                                     (clock/elapsed-since started))
        receipt (publication/completed! opts completed)]
    (if (outcome/failure-value? receipt)
      (publication-failure (failed-record completed (messages/t :chain/edge-completion-publication-failed))
                            receipt (:bindings report))
      (assoc report :edges [completed]))))

(comment
  ::edge-terminal-outcomes)
