;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-cleanup-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.phase-opsv.run-control-boundary :as boundary]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acknowledge-abort [calls]
  (swap! calls inc)
  true)

(defn- ^{:stratum 0} revoke-counted [calls failures _directory id _reason now]
  (swap! calls update id (fnil inc 0))
  (if (contains? @failures id)
    (anomaly/anomaly :unavailable "revocation unavailable" {})
    {:grant/id id :grant/revoked-at now}))

(defn- ^{:stratum 0} blocked-abort [calls entered release]
  (swap! calls inc)
  (deliver entered true)
  @release)

(defn- ^{:stratum 0} throw-revocation [exception & _]
  (throw exception))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} revocation-failures-retain-canonical-grant-identity-test
  (doseq [revoke [(constantly nil) (partial throw-revocation (java.io.IOException.))
                  (partial throw-revocation (Error.))
                  (partial throw-revocation (InterruptedException.))]]
    (let [id (random-uuid)]
      (try
        (with-redefs [grant/revoke-stored! revoke grant/current (constantly nil)]
          (let [result (boundary/revoke-with-exception-handling {} id fixtures/now)]
            (is (anomaly/anomaly? result))
            (is (= id (get-in result [:anomaly/data :grant/id])))))
        (finally (Thread/interrupted))))))

(deftest ^{:stratum 1} only-unconfirmed-cleanup-is-retried-test
  (let [supervisor (opsv/create-run-supervisor)
        aborts (atom 0)
        revocations (atom {})
        first-id (random-uuid)
        second-id (random-uuid)
        failures (atom #{second-id})
        run (opsv/register-run-control! supervisor (random-uuid) "/tmp/not-used"
              (partial acknowledge-abort aborts))
        revoke (partial revoke-counted revocations failures)]
    (doseq [id [first-id second-id]]
      (control/track-grant! (:control run) {:grant/id id} fixtures/now))
    (with-redefs [grant/revoke-stored! revoke grant/current (constantly {:grant/id second-id})]
      (is (false? (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor fixtures/now))))
      (reset! failures #{})
      (is (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor fixtures/now)))
      (is (:retired? (opsv/retire-run-control! (:control run) fixtures/now))))
    (is (= 1 @aborts))
    (is (= {first-id 1 second-id 2} @revocations))))

(deftest ^{:stratum 1} concurrent-stop-and-retirement-share-abort-confirmation-test
  (let [supervisor (opsv/create-run-supervisor)
        calls (atom 0)
        entered (promise)
        release (promise)
        run (opsv/register-run-control! supervisor (random-uuid) "/tmp/not-used"
              (partial blocked-abort calls entered release))
        stopping (future (opsv/stop-supervised-runs! supervisor fixtures/now))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [retiring (future (opsv/retire-run-control! (:control run) fixtures/now))]
        (try
          (deliver release true)
          (is (:cleanup-confirmed? (deref stopping 2000 {})))
          (is (:retired? (deref retiring 2000 {})))
          (is (= 1 @calls))
          (finally (future-cancel retiring))))
      (finally (deliver release true) (future-cancel stopping)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.run-control-cleanup-test))
