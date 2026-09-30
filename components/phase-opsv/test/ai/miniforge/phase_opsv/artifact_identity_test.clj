;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-identity-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.adapter :as adapter]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.artifacts :as artifacts]
            [ai.miniforge.phase-opsv.event-artifacts :as links]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-collection-identities [ctx directory]
  (let [descriptor [:metric-snapshot :telemetry :opsv/ramp-steps]
        vector-record (model/record ctx {:opsv/ramp-steps [1 2]} descriptor)
        list-record (model/record ctx {:opsv/ramp-steps '(1 2)} descriptor)]
    (is (not= (:artifact/id vector-record) (:artifact/id list-record)))
    (doseq [record [vector-record list-record]]
      (is (= record (artifact/publish! directory record))))))

(defn- ^{:stratum 0} assert-fatal-preflight [ctx _directory]
  (let [prepared (runtime/ensure-assembly ctx)
        fatal (anomaly/anomaly :fatal "fatal read" {})]
    (with-redefs [artifact/read-published (constantly fatal)]
      (is (= fatal (artifacts/prepare prepared))))))

(deftest ^{:stratum 0} no-confirmed-measurements-preserves-legacy-event-shape-test
  (doseq [type links/measurement-events]
    (is (= {:event/type type} (links/link {} {:event/type type})))))

(deftest ^{:stratum 0} deferred-storage-steps-are-never-realized-test
  (let [realized (atom 0)
        steps (lazy-seq (swap! realized inc) (repeat 1))
        ramp {:steps steps :environment-fingerprint {:runtime :test}}
        output {:opsv/ramp-steps steps}]
    (is (anomaly/anomaly? (adapter/ramp-shape-anomaly ramp true)))
    (is (anomaly/anomaly? (model/record {} output [:metric-snapshot :telemetry :opsv/ramp-steps])))
    (is (zero? @realized)))
  (is (anomaly/anomaly? (adapter/ramp-shape-anomaly
                        {:steps (map identity [1 2]) :environment-fingerprint {:runtime :test}} true))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} collection-types-publish-with-distinct-identities-test
  (fixtures/with-context assert-collection-identities))

(deftest ^{:stratum 1} directory-preflight-preserves-fatal-failures-test
  (fixtures/with-context assert-fatal-preflight))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.artifact-identity-test))
