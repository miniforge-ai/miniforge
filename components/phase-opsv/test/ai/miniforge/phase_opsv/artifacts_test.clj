;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifacts-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-boundary :as boundary]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-context [f]
  (let [root (.getCanonicalFile (.toFile (Files/createTempDirectory "opsv-artifacts-"
                                                                 (make-array FileAttribute 0))))
        ctx (-> (support/execution-context (support/test-adapter support/ramp-steps))
                (update :execution/input dissoc :opsv/evidence-bundle-id :opsv/evidence-refs
                        :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs)
                (assoc-in [:execution/opts :opsv/artifact-directory] (.getPath root))
                (assoc :event-stream (events/create-event-stream {:sinks []})))]
    (try (f ctx (.getPath root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(defn- ^{:stratum 0} step [ctx [phase-key transform]]
  (let [interceptor (lifecycle/interceptor {} phase-key transform)
        completed ((:leave interceptor) ((:enter interceptor) ctx))]
    (is (= :success (get-in completed [:phase :result :status])))
    (assoc-in completed [:execution/phase-results phase-key :result] (get-in completed [:phase :result]))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} lifecycle-publishes-real-content-before-evidence-references-test
  (with-context
    (fn [ctx directory]
      (let [completed (reduce step ctx support/handlers)
            output (support/phase-output completed :opsv/actuate)
            refs (:opsv/artifact-refs output)
            records (mapv #(artifact/read-published directory %) refs)
            assembly (get-in completed [:execution/input :opsv/evidence-assembly])]
        (is (= 7 (count refs)))
        (is (every? map? records))
        (is (not-any? anomaly/anomaly? records))
        (is (= (set refs) (:opsv/artifact-refs assembly)))
        (is (every? #(= (:execution/id ctx) (get-in % [:artifact/metadata :workflow/id])) records))
        (is (= (:opsv/experiment-pack output) (:artifact/content (first records))))
        (is (= [(get-in output [:opsv/phase-artifact-ids :metric-snapshot])]
               (:opsv/metric-snapshot-artifact-refs output)))
        (is (every? (set refs) (get-in output [:opsv/operational-policy :operational-policy/evidence-refs])))))))

(deftest ^{:stratum 1} identical-phase-publication-is-idempotent-test
  (with-context
    (fn [ctx directory]
      (let [prepared (runtime/ensure-assembly ctx)
            output {:opsv/experiment-pack (get-in ctx [:execution/input :opsv/experiment-pack])}
            first (boundary/publish-with-exception-handling prepared :opsv/discover output)
            retry (boundary/publish-with-exception-handling prepared :opsv/discover output)]
        (is (= first retry))
        (is (= 1 (count (.listFiles (io/file directory)))))))))

(deftest ^{:stratum 1} failed-publication-does-not-claim-a-reference-test
  (with-context
    (fn [ctx _]
      (let [prepared (runtime/ensure-assembly ctx)
            output {:opsv/actuation-record {:effective-actuation-mode :pr-only}}
            failed (with-redefs [artifact/publish! (constantly (anomaly/anomaly :unavailable "disk failed" {}))]
                     (boundary/publish-with-exception-handling prepared :opsv/actuate output))
            assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store prepared)
                        (get-in prepared [:execution/input :opsv/evidence-bundle-id]))]
        (is (anomaly/anomaly? failed))
        (is (= output (get-in failed [:anomaly/data :opsv/phase-output])))
        (is (empty? (:opsv/artifact-refs assembly)))))))

(deftest ^{:stratum 1} unavailable-assembly-refuses-before-artifact-write-test
  (with-context
    (fn [ctx directory]
      (is (anomaly/anomaly? (boundary/publish-with-exception-handling ctx :opsv/discover {})))
      (is (empty? (seq (.listFiles (io/file directory))))))))

(deftest ^{:stratum 1} fatal-publication-error-is-not-retryable-test
  (with-context
    (fn [ctx _]
      (let [prepared (runtime/ensure-assembly ctx)
            output {:opsv/actuation-record {:effective-actuation-mode :none}}
            fail! (fn [& _] (throw (AssertionError. "fatal publication")))
            result (with-redefs [artifact/publish! fail!]
                     (boundary/publish-with-exception-handling prepared :opsv/actuate output))]
        (is (= :fatal (:anomaly/type result)))
        (is (= output (get-in result [:anomaly/data :opsv/phase-output])))))))
