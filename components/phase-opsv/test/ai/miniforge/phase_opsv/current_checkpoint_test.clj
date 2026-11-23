;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.current-checkpoint-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixture]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.events :as publication]
            [ai.miniforge.phase-opsv.test-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-current [operation ctx _directory]
  (fs/with-temp-dir [directory {:prefix "opsv-checkpoint-journal-"}]
    (let [path (str (fs/real-path directory))
          options {:journal-directory path}
          stream (events/create-current-event-stream options)]
      (is (not (anomaly/anomaly? stream)) (pr-str stream))
      (when-not (anomaly/anomaly? stream)
        (try+ (operation (assoc ctx :event-stream stream) options)
          (finally (events/close-current-event-stream! stream)))))))

(defn- ^{:stratum 0} verify-reopened-retry [ctx options expected]
  (let [stream (events/create-current-event-stream options)
        [phase-key _] (last (butlast support/handlers))
        output (get-in ctx [:execution/phase-results phase-key :result :output])]
    (is (not (anomaly/anomaly? stream)) (pr-str stream))
    (when-not (anomaly/anomaly? stream)
      (try+
        (is (nil? (publication/emit-phase-events! (assoc ctx :event-stream stream) phase-key output)))
        (is (= (frequencies expected) (frequencies (events/get-events stream))))
        (is (= (filterv #(= (:execution/id ctx) (:workflow/id %)) expected)
               (events/get-events stream {:scope [:workflow (:execution/id ctx)]})))
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-checkpoint [ctx options]
  (let [completed (reduce fixture/step ctx (butlast support/handlers))
        stream (:event-stream completed)
        records (events/get-events stream)
        foreign (-> (first records) (dissoc :event/sequence-number)
                    (assoc :event/id (random-uuid) :workflow/id (random-uuid)))
        foreign-receipt (events/publish! stream foreign)
        persisted (runtime/persist completed)
        restored (runtime/ensure-assembly (dissoc persisted :opsv/evidence-assembly-store))
        bundle-id (get-in completed [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store restored) bundle-id)
        failure (anomaly/anomaly :unavailable "fixture query unavailable" {})]
    (is (= 0 (:event/sequence-number foreign-receipt)))
    (is (= (range (count records)) (map :event/sequence-number records)))
    (is (= (into {} (map (juxt :event/id :event/sequence-number)) records) (:opsv/event-sequences assembly)))
    (is (= (get-in persisted [:execution/input :opsv/evidence-assembly]) assembly))
    (doseq [path [[:event-stream] [:execution/event-stream] [:execution/opts :event-stream]]]
      (with-redefs [events/get-events (constantly failure)]
        (is (= failure (get-in (runtime/persist (assoc-in (dissoc restored :event-stream) path stream))
                              [:execution/input :opsv/evidence-snapshot])))))
    (events/close-current-event-stream! stream)
    (verify-reopened-retry restored options (conj records foreign-receipt))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-checkpoint-retains-scoped-positions-and-retries-after-reopen
  (fixture/with-context (partial with-current verify-checkpoint)))

(comment
  ::current-checkpoint)
