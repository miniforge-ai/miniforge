;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.event-replay-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as stream]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject-outcome [publish event-stream event]
  (if (= :opsv.actuation/emitted (:event/type event))
    (anomaly/anomaly :unavailable "outcome unavailable" {})
    (publish event-stream event)))

(deftest ^{:stratum 0} disposition-retries-do-not-repeat-confirmed-audits-test
  (let [{:keys [ctx calls]} (fixture/setup)
        output (phase/actuate ctx)
        transaction (first (:opsv/effect-transactions output))
        before (stream/get-events (:event-stream ctx))]
    (is (= transaction (audit/record! ctx transaction)))
    (is (= before (stream/get-events (:event-stream ctx))))
    (is (= 2 (count @calls)))))

(deftest ^{:stratum 0} failed-accumulation-retries-only-acknowledgment-test
  (let [{:keys [ctx]} (fixture/setup)
        output (phase/actuate ctx)
        before (count (stream/get-events (:event-stream ctx)))
        failed (with-redefs [evidence/accumulate-opsv-evidence! (constantly nil)]
                 (events/emit-phase-events! ctx :opsv/actuate output))]
    (is (anomaly/anomaly? failed))
    (is (nil? (events/emit-phase-events! ctx :opsv/actuate output)))
    (is (= (+ before 2) (count (stream/get-events (:event-stream ctx)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} partial-projection-and-restored-assembly-do-not-repeat-events-test
  (let [{:keys [ctx calls]} (fixture/setup)
        output (phase/actuate ctx)
        before (count (stream/get-events (:event-stream ctx)))
        publish (partial reject-outcome stream/publish!)
        failed (with-redefs [stream/publish! publish]
                 (events/emit-phase-events! ctx :opsv/actuate output))]
    (is (anomaly/anomaly? failed))
    (is (= (inc before) (count (stream/get-events (:event-stream ctx)))))
    (is (nil? (events/emit-phase-events! ctx :opsv/actuate output)))
    (is (nil? (events/emit-phase-events! ctx :opsv/actuate output)))
    (is (= (+ before 2) (count (stream/get-events (:event-stream ctx)))))
    (let [empty-stream (stream/create-event-stream {:sinks []})
          saved (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                            (get-in ctx [:execution/input :opsv/evidence-bundle-id]))
          restored (assoc ctx :event-stream empty-stream
                              :opsv/evidence-assembly-store (evidence/restore-opsv-assembly-store saved))]
      (is (nil? (events/emit-phase-events! restored :opsv/actuate output)))
      (is (empty? (stream/get-events empty-stream))))
    (is (= 2 (count @calls)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.event-replay-test))
