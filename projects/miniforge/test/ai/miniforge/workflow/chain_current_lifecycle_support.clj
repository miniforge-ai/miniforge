;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-current-lifecycle-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [babashka.fs :as fs]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} step [id]
  {:step/id id :step/workflow-id :fixture})

(defn ^{:stratum 0} directory [root child]
  (str (fs/canonicalize (fs/create-dir (fs/path root child)))))

(defn- ^{:stratum 0} loaded [& _] {:workflow {:workflow/id :fixture}})

(defn- ^{:stratum 0} execute [calls status _definition _input opts]
  (swap! calls conj opts)
  {:execution/id (:workflow-id opts) :execution/status status :execution/error "Connection refused"})

(defn ^{:stratum 0} options [directory stream]
  {:chain/retention-directory directory :chain/event-stream stream
   :event-stream (events/create-event-stream {:sinks []})})

(defn ^{:stratum 0} scope [result] [:chain (:chain/run-id result)])

(defn ^{:stratum 0} record-identity [value]
  (select-keys value [:chain/run-id :chain/definition-id :chain/definition-version]))

(defn- ^{:stratum 0} current-chain-event? [event]
  (and (= :chain (:scope/type event)) (= "2.0.0" (:event/version event))
       (not (contains? event :chain/id))))

(defn ^{:stratum 0} reject-type [publish type stream event]
  (if (= type (:event/type event))
    (anomaly/anomaly :unavailable "Fixture journal failure" {})
    (publish stream event)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} definition []
  {:chain/id :current-lifecycle :chain/version "1.0.0"
   :chain/steps [(step :first) (step :dependent)]})

(defn ^{:stratum 1} check-history [result history calls child-stream]
  (is (= (range (count history)) (map :event/sequence-number history)))
  (is (every? #(= (record-identity result) (record-identity %)) history))
  (is (every? current-chain-event? history))
  (is (= (mapv :workflow-id calls)
         (mapv :workflow/id (filter #(= :chain/step-started (:event/type %)) history))))
  (is (every? #(identical? child-stream (:event-stream %)) calls))
  (is (every? #(empty? (select-keys % [:chain/event-stream :chain/run-record :chain/definition-id])) calls)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-observed [opts status calls]
  (with-redefs [loader/load-workflow loaded runner/run-pipeline (partial execute calls status)]
    (workflow/run-chain (definition) {} opts)))

(comment
  ::current-chain-fixtures)
