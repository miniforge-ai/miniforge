;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-authority-safety-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.run-authority-fixtures :as fixture]
            [ai.miniforge.execution-grant.run-publication :as publication]
            [ai.miniforge.file-durability.interface :as durability]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} shared-write-mode
  "A protected directory does not prevent another account editing writable contents."
  "rw-rw----")

(def ^{:stratum 0 :private true} malformed-reason
  "Unpaired UTF-16 must be refused rather than silently replaced during UTF-8 encoding."
  (str (char 0xD800)))

(defn- ^{:stratum 0} pause-publication [entered release publish! & args]
  (when-not (realized? entered)
    (deliver entered true)
    @release)
  (apply publish! args))

(defn- ^{:stratum 0} sync-before-failure [synced sync! file]
  (reset! synced true)
  (sync! file))

(defn- ^{:stratum 0} failed-publication [synced & _]
  (is (true? @synced))
  (fixture/io-failure))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-permissions [root]
  (let [id (random-uuid)]
    (grant/register-run-authority! root id fixture/now)
    (Files/setPosixFilePermissions (.toPath (fixture/state-file root id))
                                   (PosixFilePermissions/fromString shared-write-mode))
    (is (anomaly/anomaly? (grant/read-run-authority root id)))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id))))))

(defn- ^{:stratum 1} verify-printer [root]
  (let [id (random-uuid)
        request (fixture/stop-request id)
        stopped (binding [*print-length* 0 *print-level* 0 *print-dup* true *print-readably* false]
                  (grant/full-stop-run-authority! root request))]
    (is (= (assoc request :authority/state :full-stop) stopped))
    (is (= stopped (grant/read-run-authority root id)))
    (is (anomaly/anomaly? (grant/full-stop-run-authority!
                           root (assoc (fixture/stop-request (random-uuid)) :authority/reason malformed-reason))))))

(defn- ^{:stratum 1} verify-reservation [root]
  (let [id (random-uuid)
        synced (atom false)]
    (with-redefs [durability/sync-ancestry! (partial sync-before-failure synced durability/sync-ancestry!)
                  publication/write-with-exception-handling! (partial failed-publication synced)]
      (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now))))
    (is (true? @synced))
    (is (anomaly/anomaly? (grant/register-run-authority! root id fixture/now)))
    (with-redefs [durability/sync-ancestry! (constantly (fixture/io-failure))]
      (is (anomaly/anomaly? (grant/register-run-authority! root (random-uuid) fixture/now))))))

(defn- ^{:stratum 1} verify-concurrent-stops [root]
  (let [id (random-uuid)
        first-request (fixture/stop-request id)
        entered (promise)
        release (promise)]
    (grant/register-run-authority! root id fixture/now)
    (with-redefs [publication/write-with-exception-handling!
                  (partial pause-publication entered release publication/write-with-exception-handling!)]
      (let [first-stop (future (grant/full-stop-run-authority! root first-request))]
        (try
          (is (= true (deref entered fixture/wait-ms :timeout)))
          (is (anomaly/anomaly? (grant/full-stop-run-authority! root (fixture/stop-request id))))
          (deliver release true)
          (is (= (assoc first-request :authority/state :full-stop) (deref first-stop fixture/wait-ms nil)))
          (finally (deliver release true) (future-cancel first-stop)))))
    (is (= (assoc first-request :authority/state :full-stop)
           (grant/full-stop-run-authority! root (fixture/stop-request id))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} writable-authority-files-refuse (fixture/call-with-root verify-permissions))

(deftest ^{:stratum 2} serialization-is-lossless-and-printer-independent (fixture/call-with-root verify-printer))

(deftest ^{:stratum 2} reservation-is-forced-before-failed-publication (fixture/call-with-root verify-reservation))

(deftest ^{:stratum 2} concurrent-stops-preserve-the-first-acknowledged-record (fixture/call-with-root verify-concurrent-stops))
