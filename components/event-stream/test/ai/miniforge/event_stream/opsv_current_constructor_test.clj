;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-current-constructor-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.opsv-constructor-cases :as cases]
            [ai.miniforge.event-stream.publication-store :as store]
            [ai.miniforge.redaction.interface :as redaction]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} canonical-messages
  ["OPSV experiment planned: pack"
   "OPSV experiment started in {:cluster \"test\", :node-pools [], :image-digests {}, :config-hash \"cfg\"}"
   "OPSV load step 1: {:rps 5} → {:rps 5}"
   "OPSV guardrail abort: :errors"
   "OPSV convergence iteration 1"
   "OPSV policy proposed: policy"
   "OPSV verification result: true"
   "OPSV actuation emitted: :recommend-only"
   "OPSV effect disposition: :unknown-outcome"
   "OPSV drift detected: :latency"])

(defn- ^{:stratum 0} current-stream []
  (with-redefs [store/durable-store (fn [_] (store/volatile-store))]
    (events/create-current-event-stream {:journal-directory "constructor-fixture"})))

(defn- ^{:stratum 0} construct-all [stream workflow-id bundle-id identity]
  (mapv (fn [[constructor _ _ payload]]
          (constructor stream workflow-id bundle-id (merge payload identity))) cases/constructors))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} constructors-preserve-canonical-payloads-and-defer-current-positions
  (let [stream (current-stream) legacy (events/create-event-stream {:sinks []})
        workflow-id (random-uuid) bundle-id (random-uuid)
        identity {:org/id (random-uuid) :workspace/id (random-uuid)
                  :repo/id "miniforge-ai/miniforge" :auth/context {:actor :fixture}}
        drafts (construct-all stream workflow-id bundle-id identity)
        old-events (construct-all legacy workflow-id bundle-id identity)
        receipts (mapv #(events/publish! stream %) drafts)]
    (is (= canonical-messages (mapv :message drafts) (mapv :message old-events)))
    (is (every? #(not (contains? % :event/sequence-number)) drafts))
    (is (empty? (:sequence-numbers @stream)))
    (is (= (range (count cases/constructors)) (map :event/sequence-number receipts)))
    (doseq [[[_ schema type payload] receipt old-event] (map vector cases/constructors receipts old-events)]
      (is (m/validate schema receipt))
      (is (m/validate schema old-event))
      (is (= :workflow (:scope/type receipt)))
      (is (= type (:event/type receipt)))
      (is (= workflow-id (:workflow/id receipt)))
      (is (= bundle-id (:opsv/evidence-bundle-id receipt)))
      (is (= identity (select-keys receipt (keys identity))))
      (is (= payload (select-keys receipt (keys payload)))))
    (is (= (first receipts) (events/publish! stream (first drafts))))))

(deftest ^{:stratum 1} identity-generation-failure-is-not-decorated-as-an-event
  (doseq [failure [(anomaly/anomaly :unavailable "fixture generator failure" {})
                  (response/make-anomaly :anomalies/unavailable "fixture legacy failure" {})]]
    (doseq [stream [(current-stream) (events/create-event-stream {:sinks []})]]
      (swap! stream assoc :snowflake-generator failure)
      (is (every? #(identical? failure %) (construct-all stream (random-uuid) (random-uuid) {})))
      (is (empty? (:sequence-numbers @stream)))
      (is (empty? (events/get-events stream))))))

(deftest ^{:stratum 1} constructor-output-remains-unredacted-until-publication
  (let [stream (current-stream)
        payload (some (fn [[_ _ type payload]] (when (= :opsv/load-step type) payload)) cases/constructors)
        draft (events/load-step stream (random-uuid) (random-uuid)
                                (assoc-in payload [:opsv/observed-load :password] "fixture-password"))
        receipt (events/publish! stream draft)]
    (is (= "fixture-password" (get-in draft [:opsv/observed-load :password])))
    (is (= (redaction/marker) (get-in receipt [:opsv/observed-load :password])))
    (is (= (:event/id draft) (:event/id receipt)))
    (is (= 0 (:event/sequence-number receipt)))))

(comment
  ::current-opsv-construction)
