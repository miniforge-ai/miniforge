;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.commit-test-support :as drafts]
            [ai.miniforge.event-stream.publication :as publication]
            [ai.miniforge.event-stream.publication-state :as state]
            [ai.miniforge.event-stream.publication-store :as store]
            [ai.miniforge.event-stream.publication-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reenter! [stream scope child observed event]
  (let [position (:event/sequence-number event)]
    (swap! observed conj [:first position])
    (when (zero? position)
      (publication/publish! stream scope child (partial reenter! stream scope child observed)))
    (swap! observed conj [:second position])))

(defn- ^{:stratum 0} fail-delivery! [_]
  (throw (ex-info "injected delivery failure" {})))

(defn- ^{:stratum 0} throw-delivery! [error _]
  (throw error))

(deftest ^{:stratum 0} receipts-are-recorded-and-delivered-once
  (let [stream (support/stream)
        scope [:workflow (random-uuid)]
        draft (drafts/draft)
        observed (atom [])
        deliver! (partial support/record! observed)
        receipt (publication/publish! stream scope draft deliver!)]
    (is (= (assoc draft :event/sequence-number 0) receipt))
    (is (= receipt (publication/publish! stream scope draft deliver!)))
    (is (= [receipt] (:events @stream)))
    (is (= [0] @observed))
    (is (anomaly/anomaly? (publication/publish! stream scope (assoc draft :message "changed") deliver!)))
    (is (= 1 (:event/sequence-number (publication/publish! stream scope (drafts/draft) deliver!))))
    (is (= [0 1] @observed))))

(deftest ^{:stratum 0} failed-storage-never-enters-log-or-delivery
  (let [failure (model/failure :unavailable :commit/write-failed nil)
        ports (store/ports (constantly failure) (constantly nil))
        stream (support/stream ports)
        observed (atom [])]
    (is (= failure (publication/publish! stream [:workflow (random-uuid)] (drafts/draft)
                                        (partial support/record! observed))))
    (is (empty? (:events @stream)))
    (is (empty? @observed))
    (is (nil? (state/pending @stream)))))

(deftest ^{:stratum 0} concurrent-delivery-follows-commit-order
  (let [stream (support/stream)
        scope [:workflow (random-uuid)]
        observed (atom [])
        deliver! (partial support/record! observed)
        count-events 32
        jobs (mapv (fn [_] (future (publication/publish! stream scope (drafts/draft) deliver!)))
                   (range count-events))
        receipts (mapv deref jobs)]
    (is (= (set (range count-events)) (set (map :event/sequence-number receipts))))
    (is (= (vec (range count-events)) @observed))
    (is (= @observed (mapv :event/sequence-number (:events @stream))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} reentrant-delivery-cannot-overtake-the-current-event
  (let [stream (support/stream)
        scope [:workflow (random-uuid)]
        observed (atom [])
        deliver! (partial reenter! stream scope (drafts/draft) observed)]
    (publication/publish! stream scope (drafts/draft) deliver!)
    (is (= [[:first 0] [:second 0] [:first 1] [:second 1]] @observed))
    (is (= [0 1] (mapv :event/sequence-number (:events @stream))))
    (is (nil? (state/pending @stream)))
    (is (false? (get-in @stream [:publication :draining?])))))

(deftest ^{:stratum 1} failed-drainer-retains-committed-event-for-retry
  (let [stream (support/stream)
        scope [:workflow (random-uuid)]
        draft (drafts/draft)
        receipt (publication/publish! stream scope draft fail-delivery!)
        observed (atom [])]
    (is (= 0 (:event/sequence-number receipt)))
    (is (= receipt (state/pending @stream)))
    (is (anomaly/anomaly? (get-in @stream [:publication :delivery-failure])))
    (is (= receipt (publication/publish! stream scope draft (partial support/record! observed))))
    (is (= [0] @observed))
    (is (nil? (get-in @stream [:publication :delivery-failure])))
    (publication/close! stream)
    (is (anomaly/anomaly? (publication/publish! stream scope draft (partial support/record! observed))))
    (is (= [0] @observed))))

(deftest ^{:stratum 1} critical-delivery-retains-replayable-record-and-releases-drainer
  (doseq [critical [(AssertionError. "test") (InterruptedException. "test")]
          thrown [critical (ex-info "wrapper" {} critical)]]
    (let [stream (support/stream)
          scope [:workflow (random-uuid)]
          draft (drafts/draft)
          deliver! (partial throw-delivery! thrown)
          publish! (partial publication/publish! stream scope draft deliver!)
          [caught interrupted?] (support/observe-critical! publish!)]
      (is (identical? critical caught))
      (is (= (instance? InterruptedException critical) interrupted?))
      (is (= 1 (count (:events @stream))))
      (is (= 0 (:event/sequence-number (state/pending @stream))))
      (is (false? (get-in @stream [:publication :draining?]))))))

(comment
  ::commit-before-delivery)
