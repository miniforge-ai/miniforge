;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.terminal-test-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as pr]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} recovery-output [ctx]
  (let [recovered (opsv/recover-actuation-evidence! ctx)]
    (if (anomaly/anomaly? recovered) recovered
      (get-in recovered [:phase :result :output :anomaly/data :opsv/phase-output]))))

(defn- ^{:stratum 0} refuse-material [publish kind directory record]
  (if (= kind (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable "publication refused" {})
    (publish directory record)))

(defn- ^{:stratum 0} configured-context [ctx]
  (-> (f/configured ctx)
      (assoc :execution/status :running)
      (assoc-in [:execution/opts :opsv/governance] governance/policy)
      (assoc-in [:execution/input :opsv/experiment-pack :experiment-pack/actuation-intent] :pr-only)))

(defn- ^{:stratum 0} bind-runtime [ready runtime]
  (let [hash (:opsv/policy-hash (support/phase-output ready :opsv/verify))]
    (assoc-in ready [:execution/opts :opsv/pr-execution]
              (assoc-in runtime [:target :opsv/policy-hash] hash))))

(defn- ^{:stratum 0} remove-provider-fixture [runtime]
  (doseq [file (reverse (file-seq (io/file (get-in runtime [:provider :directory]))))]
    (io/delete-file file)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-with-publication-failure [ready kind]
  (let [publish (partial refuse-material artifact/publish! kind)
        emit (if (= :event kind)
               (constantly (anomaly/anomaly :unavailable "event refused" {}))
               events/emit-phase-events!)]
    (with-redefs [artifact/publish! publish events/emit-phase-events! emit]
      (f/run-last-phase ready))))

(defn- ^{:stratum 1} call-with-pr-runtime [test-fn ctx directory]
  (let [{:keys [runtime calls]} (pr/setup)
        configured (configured-context ctx)]
    (try
      (let [ready (reduce f/step configured (butlast support/handlers))]
        (test-fn (bind-runtime ready runtime) directory calls))
      (finally (remove-provider-fixture runtime)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-ready [test-fn]
  (f/with-context (partial call-with-pr-runtime test-fn)))

(comment
  (with-ready (fn [_ready directory _calls] directory)))
