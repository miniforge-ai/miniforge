;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-runtime-options-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.boundary.chain-runtime-options :as options]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.chain-composition :as composition]
            [ai.miniforge.workflow.chain-test-support :as support]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected [calls & args]
  (swap! calls conj args)
  nil)

(deftest ^{:stratum 0} legacy-options-remain-values-without-retaining-map-comparators
  (is (= {} (options/prepare nil)))
  (is (= {} (options/prepare {})))
  (is (= {"unrelated" true} (options/prepare (sorted-map "unrelated" true)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} invalid-current-options-refuse-before-identity-storage-or-execution
  (let [calls (atom [])
        current (atom {:publication {:directory "/journal"}})
        legacy (events/create-event-stream {:sinks []})
        definition (support/definition :runtime-options "1.0.0")]
    (with-redefs [composition/run-record (partial unexpected calls)
                  artifact/publish! (partial unexpected calls)
                  chain/run-chain (partial unexpected calls)]
      (doseq [opts [42 []
                    {:chain/event-stream nil :chain/retention-directory "/controlled"}
                    {:chain/event-stream legacy :chain/retention-directory "/controlled"}
                    {:chain/event-stream current}
                    {:chain/event-stream current :chain/retention-directory nil}
                    {:chain/event-stream current :chain/retention-directory ""}
                    {:chain/event-stream current :chain/retention-directory "/journal"}
                    {:chain/event-stream current :chain/retention-directory "/controlled" :event-stream current}]]
        (is (anomaly/anomaly? (workflow/run-chain definition {} opts))))
      (is (empty? @calls)))))

(comment
  (options/prepare {}))
