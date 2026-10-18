;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-journal-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-journal-test-support :as support]
            [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.journal-record :as record]
            [ai.miniforge.event-stream.journal-storage :as storage]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-reopen [directory]
  (let [scope [:workflow (random-uuid)]
        draft (support/draft)
        store (storage/open! directory)
        event (storage/commit! store scope draft)]
    (try
      (is (= (assoc draft :event/sequence-number 0) event))
      (is (anomaly/anomaly? (storage/open! directory)))
      (is (= event (storage/commit! store scope (assoc draft :event/timestamp (java.util.Date. 0)))))
      (is (= 1 (count (artifact/list-published directory))))
      (finally (storage/close! store)))
    (is (anomaly/anomaly? (storage/commit! store scope draft)))
    (let [reopened (storage/open! directory)]
      (try
        (is (= event (storage/commit! reopened scope draft)))
        (is (= 1 (:event/sequence-number (storage/commit! reopened scope (support/draft)))))
        (is (= 0 (:event/sequence-number (storage/commit! reopened [:pr (second scope)] (support/draft)))))
        (finally (storage/close! reopened))))))

(defn- ^{:stratum 0} check-unconfirmed [directory]
  (let [scope [:workflow (random-uuid)]
        draft (support/draft)
        store (storage/open! directory)
        publish! artifact/publish!
        failure (model/failure :unavailable :journal/recovery draft)]
    (try
      (with-redefs [artifact/publish! (comp (constantly failure) publish!)]
        (is (= failure (storage/commit! store scope draft))))
      (is (anomaly/anomaly? (storage/commit! store scope (support/draft))))
      (is (= 1 (count (artifact/list-published directory))))
      (finally (storage/close! store)))
    (let [reopened (storage/open! directory)]
      (try
        (is (= (assoc draft :event/sequence-number 0) (storage/commit! reopened scope draft)))
        (is (= 1 (:event/sequence-number (storage/commit! reopened scope (support/draft)))))
        (finally (storage/close! reopened))))))

(defn- ^{:stratum 0} check-failed-recovery [directory]
  (let [scope [:workflow (random-uuid)]
        event (assoc (support/draft) :event/sequence-number 0)
        value (record/wrap-event scope event)
        failure (model/failure :unavailable :journal/recovery event)]
    (is (= value (artifact/publish! directory value)))
    (with-redefs [artifact/publish! (constantly failure)]
      (is (= failure (storage/open! directory))))
    (let [reopened (storage/open! directory)]
      (try
        (is (= event (storage/commit! reopened scope event)))
        (finally (storage/close! reopened))))))

(defn- ^{:stratum 0} check-corrupt-recovery [directory]
  (let [scope [:workflow (random-uuid)]
        event (assoc (support/draft) :event/sequence-number 1)
        value (record/wrap-event scope event)]
    (is (= value (artifact/publish! directory value)))
    (is (anomaly/anomaly? (storage/open! directory)))
    ;; A second recovery must fail for the same corruption, not a leaked owner.
    (is (= :fault (:anomaly/type (storage/open! directory))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} reopen-preserves-identities-values-and-scope-positions
  (support/with-directory check-reopen))

(deftest ^{:stratum 1} uncertain-write-is-recovered-without-reusing-its-position
  (support/with-directory check-unconfirmed))

(deftest ^{:stratum 1} recovery-reconfirms-durability-and-releases-failed-owner
  (support/with-directory check-failed-recovery)
  (support/with-directory check-corrupt-recovery))

(comment
  ::durable-journal-acceptance)
