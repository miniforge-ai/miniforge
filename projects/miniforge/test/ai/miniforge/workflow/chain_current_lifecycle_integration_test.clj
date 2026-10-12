;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-current-lifecycle-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.chain-current-lifecycle-support :as support :refer [options scope record-identity reject-type check-history run-observed]]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} lifecycle-is-durable-run-scoped-and-caller-owned
  (fs/with-temp-dir [root {:prefix "chain-current-lifecycle-"}]
    (let [directory (support/directory root "retention")
          journal (support/directory root "journal")
          stream (events/create-current-event-stream {:journal-directory journal})
          opts (options directory stream) calls (atom [])]
      (try+
        (let [result (run-observed opts :completed calls)
              history (events/get-events stream {:scope (scope result)})
              another (run-observed opts :completed (atom []))]
          (is (= :completed (:chain/status result)))
          (is (= [:chain/started :chain/step-started :chain/step-completed
                  :chain/step-started :chain/step-completed :chain/completed] (mapv :event/type history)))
          (check-history result history @calls (:event-stream opts))
          (is (not= (:chain/run-id result) (:chain/run-id another)))
          (is (= (record-identity result)
                 (record-identity (workflow/load-chain-run directory (:chain/run-id result)))))
          (is (empty? (events/get-events stream {:workflow-id (:workflow-id (first @calls))})))
          (events/close-current-event-stream! stream)
          (let [reopened (events/create-current-event-stream {:journal-directory journal})]
            (is (not (anomaly/any-anomaly? reopened)) (pr-str reopened))
            (when (events/current-event-stream? reopened)
              (try+
                (is (= history (events/get-events reopened {:scope (scope result)})))
                (finally (events/close-current-event-stream! reopened))))))
        (finally (events/close-current-event-stream! stream))))))

(deftest ^{:stratum 0} child-failure-is-classified-and-stops-dependent-execution
  (fs/with-temp-dir [root {:prefix "chain-current-failure-"}]
    (let [directory (support/directory root "retention")
          journal (support/directory root "journal")
          stream (events/create-current-event-stream {:journal-directory journal})
          calls (atom []) opts (options directory stream)]
      (try+
        (let [result (run-observed opts :failed calls)
              history (events/get-events stream {:scope (scope result)})
              failures (filter :failure/class history)]
          (is (= :failed (:chain/status result)))
          (is (= 1 (count @calls)))
          (is (= [:chain/started :chain/step-started :chain/step-failed :chain/failed] (mapv :event/type history)))
          (is (= 2 (count failures)))
          (is (every? #(failure/valid-failure-class? (:failure/class %)) failures))
          (is (= :first (:chain/failed-step (last history))))
          (check-history result history @calls (:event-stream opts)))
        (finally (events/close-current-event-stream! stream))))))

(deftest ^{:stratum 0} rejected-current-publication-stops-effects-and-keeps-fallbacks-typed
  (doseq [[type expected-count expected]
          [[:chain/started 0 [:chain/failed]]
           [:chain/step-started 0 [:chain/started :chain/failed]]
           [:chain/step-completed 1 [:chain/started :chain/step-started :chain/step-failed :chain/failed]]
           [:chain/completed 2 [:chain/started :chain/step-started :chain/step-completed
                                :chain/step-started :chain/step-completed :chain/failed]]]]
    (fs/with-temp-dir [root {:prefix "chain-current-rejection-"}]
      (let [directory (support/directory root "retention")
            journal (support/directory root "journal")
            stream (events/create-current-event-stream {:journal-directory journal})
            calls (atom []) opts (options directory stream) publish events/publish!]
        (try+
          (with-redefs [events/publish! (partial reject-type publish type)]
            (let [result (run-observed opts :completed calls)
                  identity (:anomaly/data result)]
              (is (anomaly/any-anomaly? result))
              (is (= (record-identity identity)
                     (record-identity (workflow/load-chain-run directory (:chain/run-id identity)))))))
          (is (= expected-count (count @calls)))
          (is (= expected (mapv :event/type (events/get-events stream))))
          (finally (events/close-current-event-stream! stream)))))))

(deftest ^{:stratum 0} shared-directory-is-refused-without-poisoning-the-journal
  (fs/with-temp-dir [root {:prefix "chain-current-storage-boundary-"}]
    (let [directory (str (fs/canonicalize root))
          stream (events/create-current-event-stream {:journal-directory directory})
          calls (atom [])]
      (try+
        (is (= :invalid-input (:anomaly/type (run-observed (options directory stream) :completed calls))))
        (is (empty? @calls))
        (is (empty? (events/get-events stream)))
        (finally (events/close-current-event-stream! stream)))
      (let [reopened (events/create-current-event-stream {:journal-directory directory})]
        (is (not (anomaly/any-anomaly? reopened)) (pr-str reopened))
        (when (events/current-event-stream? reopened)
          (events/close-current-event-stream! reopened))))))

(deftest ^{:stratum 0} closed-current-stream-cannot-fall-back-to-the-child-stream
  (fs/with-temp-dir [root {:prefix "chain-current-closed-"}]
    (let [directory (support/directory root "retention")
          journal (support/directory root "journal")
          stream (events/create-current-event-stream {:journal-directory journal})
          opts (options directory stream) calls (atom [])]
      (events/close-current-event-stream! stream)
      (is (anomaly/any-anomaly? (run-observed opts :completed calls)))
      (is (empty? @calls))
      (is (empty? (events/get-events (:event-stream opts)))))))

(comment
  ::current-chain-lifecycle)
