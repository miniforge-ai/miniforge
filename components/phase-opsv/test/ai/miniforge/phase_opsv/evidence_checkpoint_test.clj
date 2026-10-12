;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.evidence-checkpoint-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.events :as publication]
            [ai.miniforge.phase-opsv.evidence-base :as base]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.evidence-snapshot :as snapshot]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected-decode [_]
  (throw (AssertionError. "Live assembly must not decode a checkpoint")))

(defn- ^{:stratum 0} assert-corruption-rejected [ctx _directory]
      (let [completed (f/step ctx (first support/handlers))
            detached (dissoc completed :opsv/evidence-assembly-store)
            corrupt (assoc-in detached [:execution/input :opsv/evidence-snapshot] "corrupt")
            wrong-run (assoc detached :execution/id (random-uuid))
            wrong-bundle (assoc-in detached [:execution/input :opsv/evidence-bundle-id] (random-uuid))
            encoded (get-in detached [:execution/input :opsv/evidence-snapshot])]
        (is (anomaly/anomaly? (runtime/ensure-assembly corrupt)))
        (is (anomaly/anomaly? (runtime/ensure-assembly wrong-run)))
        (is (anomaly/anomaly? (runtime/ensure-assembly wrong-bundle)))
        (is (anomaly/anomaly? (snapshot/decode detached :wrong-material-kind encoded)))
        (is (not (anomaly/anomaly? (runtime/ensure-assembly detached))))))

(defn- ^{:stratum 0} assert-encoding-failure [ctx _directory]
  (doseq [[boundary failure] [[#'artifact/encode-snapshot (anomaly/anomaly :unavailable "snapshot unavailable" {})]
                             [#'evidence/accumulate-opsv-evidence! (response/make-anomaly :anomalies/conflict "sealed")]]]
      (let [[phase-key transform] (first support/handlers)
            interceptor (lifecycle/interceptor {} phase-key transform)
            entered ((:enter interceptor) ctx)
            unavailable (constantly failure)
            completed (with-redefs-fn {boundary unavailable} (partial (:leave interceptor) entered))]
        (is (= :failed (get-in completed [:phase :status])))
        (is (= :error (get-in completed [:phase :result :status])))
        (is (map? (get-in completed [:phase :result :output :anomaly/data :opsv/phase-output])))
        (is (= failure (get-in completed [:execution/input :opsv/evidence-snapshot]))))))

(defn- ^{:stratum 0} assert-publication-retry [ctx _directory]
  (loop [ready ctx handlers (butlast support/handlers)]
    (when-let [[phase-key transform] (first handlers)]
      (let [interceptor (lifecycle/interceptor {} phase-key transform)
            entered ((:enter interceptor) ready)
            output (get-in entered [:phase :result :output])
            failure (anomaly/anomaly :unavailable "injected checkpoint failure" {})
            failed (with-redefs [artifact/encode-snapshot (constantly failure)]
                     ((:leave interceptor) entered))
            before (events/get-events (:event-stream failed))]
        (is (= :failed (get-in failed [:phase :status])))
        (is (nil? (publication/emit-phase-events! failed phase-key output)))
        (is (= before (events/get-events (:event-stream failed))))
        (let [original (base/bundle failed)
              persisted (runtime/persist failed)
              restored (runtime/ensure-assembly
                         (assoc (dissoc persisted :opsv/evidence-assembly-store)
                                :event-stream (events/create-event-stream)))]
          (is (= (count before) (get-in original [:evidence/event-links 0 :event-links/event-count] 0)))
          (is (= (:evidence/event-links original) (:evidence/event-links (base/bundle restored)))))
        (let [completed ((:leave interceptor) entered)
              result (get-in completed [:phase :result])]
          (is (= :success (:status result)))
          (recur (assoc-in completed [:execution/phase-results phase-key :result] result)
                 (rest handlers)))))))

(deftest ^{:stratum 0} evidence-tier-ignores-caller-outcome-test
  (is (= :standard (get-in (base/bundle {}) [:evidence/outcome :outcome/tier])))
  (let [ctx {:execution/workflow {:workflow/tier :critical}}
        captured (base/bundle ctx)]
    (is (= :critical (get-in captured [:evidence/outcome :outcome/tier])))
    (doseq [tier [:standard nil]]
      (is (= :critical (get-in (base/bundle (assoc-in ctx [:execution/opts :opsv/evidence-base :evidence/outcome :outcome/tier] tier))
                              [:evidence/outcome :outcome/tier]))))))

(defn- ^{:stratum 0} assert-finalized-snapshot [ctx _directory]
  (let [completed (f/step ctx (first support/handlers))
        store (:opsv/evidence-assembly-store completed)
        bundle-id (get-in completed [:execution/input :opsv/evidence-bundle-id])]
    (swap! store update bundle-id assoc :opsv.assembly/status :finalized)
    (let [before @store
          persisted (with-redefs [events/get-events (constantly ::unexpected-query)] (runtime/persist completed))
          encoded (get-in persisted [:execution/input :opsv/evidence-snapshot])]
      (is (= before @store))
      (is (= (get before bundle-id) (snapshot/decode persisted :checkpoint encoded))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-live-store-skips-decoding [ctx _directory]
  (let [completed (f/step ctx (first support/handlers))
        store (:opsv/evidence-assembly-store completed)
        entered (with-redefs [artifact/decode-snapshot unexpected-decode]
                  (runtime/ensure-assembly completed))]
    (is (identical? store (:opsv/evidence-assembly-store entered)))
    (is (= (get-in completed [:execution/input :opsv/evidence-assembly])
           (get-in entered [:execution/input :opsv/evidence-assembly])))))

(deftest ^{:stratum 1} checkpoint-corruption-cannot-fall-back-to-display-map-test
  (f/with-context assert-corruption-rejected))

(deftest ^{:stratum 1} failed-snapshot-cannot-report-phase-success-test
  (f/with-context assert-encoding-failure))

(deftest ^{:stratum 1} checkpoint-retry-does-not-duplicate-phase-audit-events-test
  (f/with-context assert-publication-retry))

(deftest ^{:stratum 1} finalized-assembly-is-checkpointed-without-further-accumulation
  (f/with-context assert-finalized-snapshot))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} live-assembly-does-not-decode-durable-snapshots-test
  (f/with-context assert-live-store-skips-decoding))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.evidence-checkpoint-test))
