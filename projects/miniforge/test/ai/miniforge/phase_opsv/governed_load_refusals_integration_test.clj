;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-load-refusals-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.retained-preparation-test-support :as storage]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-retention [{:keys [ctx input directory id]}]
  (storage/withdraw! directory id)
  (is (= :unavailable (:anomaly/type (phase/prepare-governed-kubernetes-load-proposal! ctx input)))))

(defn- ^{:stratum 0} check-inputs [{:keys [ctx input]}]
  (doseq [[path value] [[[:runtime :load/image] "fortio/fortio:latest"]
                        [[:runtime :load/cpu-millis] 0] [[:runtime :load/memory-mib] 0]
                        [[:runtime :command] ["sh"]] [[:runtime :credentials] "forged"]
                        [[:limits :opsv/duration-ms] 0] [[:limits :opsv/duration-ms] 1500]
                        [[:limits :opsv/load-limits :load/max-requests] 0]
                        [[:limits :opsv/load-limits :load/max-concurrency] 0]
                        [[:limits :opsv/load-limits :load/max-concurrency] 101]
                        [[:limits :opsv/load-limits :load/max-rate-per-second] 0]
                        [[:limits :opsv/traffic-destinations] []]
                        [[:limits :opsv/traffic-destinations] ["https://a/" "https://b/"]]
                        [[:limits :opsv/traffic-destinations] ["file:///tmp/target"]]
                        [[:limits :opsv/traffic-destinations] ["https://user:secret@host/"]]
                        [[:limits :opsv/traffic-destinations] ["https://host/#ignored"]]
                        [[:limits :opsv/traffic-destinations] ["https://host/%7Buuid%7D"]]
                        [[:limits :opsv/traffic-destinations] ["https://host/prefix/%7buuid%7d/suffix"]]
                        [[:limits :opsv/traffic-destinations] ["https://host/%7Buuid%7D/%7Buuid%7D"]]
                        [[:limits :opsv/traffic-destinations] ["invalid URI"]]
                        [[:limits :environment/class] :production]
                        [[:pod/name] "caller-owned-resource"]]]
    (is (= :invalid-input (:anomaly/type (phase/prepare-governed-kubernetes-load-proposal!
                                         ctx (assoc-in input path value)))))))

(defn- ^{:stratum 0} check-targets [{:keys [ctx input]}]
  (doseq [[path value] [[[:declaration :workflow/id] (random-uuid)]
                        [[:declaration :kubernetes/deployment-name] "foreign"]
                        [[:declaration :runtime/target :namespace-uid] "foreign"]
                        [[:request :verification/id] (random-uuid)]
                        [[:request :candidate/policy :operational-policy/version] "2.0.0"]
                        [[:request :experiment-pack :experiment-pack/version] "2.0.0"]
                        [[:comparison/artifact-ref] (random-uuid)]]]
    (is (anomaly/anomaly? (phase/prepare-governed-kubernetes-load-proposal! ctx (assoc-in input path value))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} withdrawn-original-cannot-produce-a-load-proposal-test
  (fixtures/with-input check-retention))

(deftest ^{:stratum 1} unbounded-or-caller-selected-worker-behavior-is-refused-test
  (fixtures/with-input check-inputs))

(deftest ^{:stratum 1} foreign-declaration-or-attempt-cannot-produce-a-load-proposal-test
  (fixtures/with-input check-targets))

(comment ::no-live-network-or-provider-mutations)
