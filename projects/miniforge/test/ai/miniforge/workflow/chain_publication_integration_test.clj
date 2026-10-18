;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-publication-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.loader :as loader]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retained-invocation [directory]
  (->> (artifact/list-published directory)
       (filter #(= :invocation (get-in % [:artifact/content :chain/record-kind])))
       first :artifact/content :chain/record))

(defn- ^{:stratum 0} unexpected-load [calls & args]
  (swap! calls conj args)
  (anomaly/anomaly :unavailable "Unexpected child load" {}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish [directory observed failure _stream event]
  (let [record (retained-invocation directory)]
    (swap! observed conj [(:event/type event) record])
    (if (= :chain/started (:event/type event)) failure event)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} retained-admission-survives-start-publication-failure-without-child-effects
  (fs/with-temp-dir [root {:prefix "chain-publication-gate-"}]
    (let [directory (str (fs/canonicalize root))
          observed (atom []) calls (atom [])
          failure (anomaly/anomaly :unavailable "Start publication unavailable" {})
          definition {:chain/id :retained-publication
                      :chain/version "1.0.0"
                      :chain/steps [{:step/id :only :step/workflow-id :unused}]}
          opts {:chain/retention-directory directory
                :event-stream (events/create-event-stream {:sinks []})}]
      (with-redefs [events/publish! (partial publish directory observed failure)
                    loader/load-workflow (partial unexpected-load calls)]
        (let [result (workflow/run-chain definition {} opts)
              records (mapv second @observed)
              record (first records)]
          (is (= failure result))
          (is (= [:chain/started :chain/failed] (mapv first @observed)))
          (is (uuid? (:chain/run-id record)))
          (is (= [record record] records))
          (is (= record (workflow/load-chain-run directory (:chain/run-id record))))
          (is (empty? @calls)))))))

(comment
  ::durable-admission-with-failed-publication)
