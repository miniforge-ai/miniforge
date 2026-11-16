;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-authority-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.run-authority-fixtures :as fixture]
            [ai.miniforge.execution-grant.run-publication :as publication]
            [ai.miniforge.execution-grant.run-storage :as storage]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} private-mode
  "Authority files remain owner-only even when ancestor directories permit reads."
  "rw-------")

(defn- ^{:stratum 0} fatal [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} interrupted [& _] (throw (InterruptedException.)))

(defn- ^{:stratum 0} verify-grant-lookup [root]
  (let [issued (fixture/issued-grant)]
    (is (= issued (grant/register! (str root) issued)))
    (is (= issued (grant/current (str root) (:grant/id issued))))))

(defn- ^{:stratum 0} verify-early-stop [root]
  (let [id (random-uuid)
        stopped (grant/full-stop-run-authority! root (fixture/stop-request id))]
    (is (= :full-stop (:authority/state stopped)))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (is (= stopped (grant/read-run-authority root id)))))

(defn- ^{:stratum 0} verify-missing-state [root]
  (let [id (random-uuid)]
    (is (anomaly/anomaly? (grant/read-run-authority root id)))
    (is (= :active (:authority/state (grant/register-run-authority! root id fixture/now))))
    (is (.delete (fixture/state-file root id)))
    (is (anomaly/anomaly? (grant/read-run-authority root id)))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (is (= :full-stop (:authority/state (grant/full-stop-run-authority! root (fixture/stop-request id)))))))

(defn- ^{:stratum 0} verify-corruption [root]
  (let [id (random-uuid)
        foreign (random-uuid)]
    (grant/register-run-authority! root id fixture/now)
    (grant/register-run-authority! root foreign fixture/now)
    (spit (fixture/state-file root id) (slurp (fixture/state-file root foreign)))
    (is (anomaly/anomaly? (grant/read-run-authority root id)))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id))))
    (spit (fixture/state-file root id) (str (slurp (fixture/state-file root foreign)) " {}"))
    (is (anomaly/anomaly? (grant/read-run-authority root id)))))

(defn- ^{:stratum 0} verify-uncertain-publication [root]
  (let [id (random-uuid)
        publish! durability/publish-bytes!]
    (grant/register-run-authority! root id fixture/now)
    (with-redefs [durability/publish-bytes! (partial fixture/publish-then-fail publish!)]
      (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id)))))
    (is (= :full-stop (:authority/state (grant/read-run-authority root id))))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))))

(defn- ^{:stratum 0} verify-unqualified-profile [root]
  (with-redefs [durability/filesystem-profile (constantly :native-defaults)]
    (is (= :unavailable (:anomaly/type (grant/register-run-authority! root (random-uuid) fixture/now))))))

(defn- ^{:stratum 0} verify-linked-state [root]
  (let [id (random-uuid)
        foreign (random-uuid)]
    (grant/register-run-authority! root id fixture/now)
    (grant/register-run-authority! root foreign fixture/now)
    (let [file (fixture/state-file root id)]
      (is (.delete file))
      (Files/createSymbolicLink (.toPath file) (.toPath (fixture/state-file root foreign))
                                (make-array java.nio.file.attribute.FileAttribute 0)))
    (is (anomaly/anomaly? (grant/read-run-authority root id)))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id))))))

(defn- ^{:stratum 0} verify-admission-race [root]
  (let [id (random-uuid)
        request (fixture/stop-request id)
        write! publication/write-with-exception-handling!]
    (with-redefs [publication/write-with-exception-handling!
                  (partial fixture/stop-before-registration! root request write!)]
      (is (= :active (:authority/state (grant/register-run-authority! root id fixture/now)))))
    (is (= :full-stop (:authority/state (grant/full-stop-run-authority! root request))))))

(defn- ^{:stratum 0} verify-parallel-admission [root]
  (let [id (random-uuid)
        ready (promise)
        registration (future @ready (grant/register-run-authority! root id fixture/now))
        stopped (future @ready (grant/full-stop-run-authority! root (fixture/stop-request id)))]
    (deliver ready true)
    (is (map? (deref registration fixture/wait-ms nil)))
    (is (map? (deref stopped fixture/wait-ms nil)))
    (is (= :full-stop (:authority/state (grant/full-stop-run-authority! root (fixture/stop-request id)))))
    (is (= :full-stop (:authority/state (grant/read-run-authority root id))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-durable-stop [root]
  (let [id (random-uuid)
        request (fixture/stop-request id)
        active (grant/register-run-authority! root id fixture/now)
        stopped (grant/full-stop-run-authority! root request)]
    (is (= :active (:authority/state active)))
    (is (= fixture/now (:authority/created-at active)))
    (is (= (assoc request :authority/state :full-stop) stopped))
    (is (= stopped (grant/read-run-authority (str root) id)))
    (is (= stopped (grant/full-stop-run-authority! root (fixture/stop-request id))))
    (is (= :unauthorized (:anomaly/type (grant/register-run-authority! root id fixture/now))))
    (is (= private-mode (PosixFilePermissions/toString
                        (Files/getPosixFilePermissions (.toPath (fixture/state-file root id))
                                                      (make-array java.nio.file.LinkOption 0)))))))

(defn- ^{:stratum 1} verify-interrupted-write [root]
  (try
    (with-redefs [durability/publish-bytes! interrupted]
      (let [result (grant/full-stop-run-authority! root (fixture/stop-request (random-uuid)))
            interrupted? (Thread/interrupted)]
        (is (anomaly/anomaly? result))
        (is interrupted?)))
    (finally (Thread/interrupted))))

(deftest ^{:stratum 1} malformed-boundary-input-never-reaches-storage
  (with-redefs [storage/directory fatal]
    (is (= :invalid-input (:anomaly/type (grant/register-run-authority! nil (random-uuid) fixture/now))))
    (is (= :invalid-input (:anomaly/type (grant/read-run-authority "" (random-uuid)))))
    (doseq [request (fixture/invalid-stop-requests)]
      (is (= :invalid-input (:anomaly/type (grant/full-stop-run-authority! "root" request)))))))

(deftest ^{:stratum 1} existing-grant-records-retain-their-own-identity (fixture/call-with-root verify-grant-lookup))

(deftest ^{:stratum 1} stop-dominates-late-registration (fixture/call-with-root verify-early-stop))

(deftest ^{:stratum 1} missing-reserved-state-never-means-permission (fixture/call-with-root verify-missing-state))

(deftest ^{:stratum 1} corrupt-or-mismatched-state-fails-closed (fixture/call-with-root verify-corruption))

(deftest ^{:stratum 1} failed-post-publication-confirmation-never-reactivates (fixture/call-with-root verify-uncertain-publication))

(deftest ^{:stratum 1} native-defaults-cannot-qualify-run-authority (fixture/call-with-root verify-unqualified-profile))

(deftest ^{:stratum 1} linked-state-cannot-be-used-or-overwritten (fixture/call-with-root verify-linked-state))

(deftest ^{:stratum 1} competing-stop-is-refused-during-admission (fixture/call-with-root verify-admission-race))

(deftest ^{:stratum 1} simultaneous-stop-and-admission-cannot-reopen (fixture/call-with-root verify-parallel-admission))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} durable-stop-is-idempotent-and-cannot-reopen (fixture/call-with-root verify-durable-stop))

(deftest ^{:stratum 2} interrupted-publication-retains-the-interrupt (fixture/call-with-root verify-interrupted-write))
