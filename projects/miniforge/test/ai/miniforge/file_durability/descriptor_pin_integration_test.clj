;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.descriptor-pin-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.descriptor-process :as process]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.descriptor-pin-fixtures :as fixtures]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-replacement! [path held handle]
  (let [before (fixtures/path-identity path)
        previous (fs/path (fs/parent path) "original")]
    (is (= before (lease/identity-of handle)))
    (fs/move path previous)
    (fs/create-dir path)
    (is (not= before (fixtures/path-identity path)))
    (is (= before (lease/identity-of handle)))
    (is (lease/live? handle))
    (lease/release! handle)
    (is (false? (lease/live? handle)))
    (is (nil? (lease/identity-of handle)))
    (is (false? (.isAlive ^Process @held)))
    (lease/release! handle)))

(defn- ^{:stratum 0} verify-host-eof! [held handle]
  (.close (.getOutputStream ^Process @held))
  (is (fixtures/exited? @held))
  (is (false? (lease/live? handle)))
  (is (nil? (lease/identity-of handle))))

(defn- ^{:stratum 0} verify-death! [held handle]
  (.destroyForcibly ^Process @held)
  (is (fixtures/exited? @held))
  (is (false? (lease/live? handle)))
  (is (nil? (lease/identity-of handle))))

(defn- ^{:stratum 0} verify-refusal! [held result]
  (is (anomaly/anomaly? result))
  (is (false? (.isAlive ^Process @held))))

(defn- ^{:stratum 0} host-loss! [verify! parent]
  (let [held (volatile! nil)]
    (fixtures/with-helper! held process/start! (fs/path parent) fixtures/timeout-ms
                           (partial verify! held))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} replacement! [parent]
  (let [path (fs/create-dir (fs/path parent "root"))
        held (volatile! nil)]
    (fixtures/with-helper! held process/start! path fixtures/timeout-ms
                           (partial verify-replacement! path held))))

(defn- ^{:stratum 1} refused-program! [program parent]
  (let [held (volatile! nil)
        launch! (partial fixtures/launch-program! program)]
    (fixtures/with-helper! held launch! (fs/path parent) fixtures/receipt-timeout-ms
                           (partial verify-refusal! held))))

(defn- ^{:stratum 1} refused-target! [parent]
  (let [held (volatile! nil)
        file (fs/path parent "file")
        link (fs/path parent "link")]
    (fs/create-file file)
    (fs/create-sym-link link (fs/path parent))
    (doseq [path [file link]]
      (fixtures/with-helper! held process/start! path fixtures/timeout-ms
                             (partial verify-refusal! held)))))

(deftest ^{:stratum 1 :integration true} test-host-pipe-loss-releases-native-descriptor
  (support/with-root (partial host-loss! verify-host-eof!)))

(deftest ^{:stratum 1 :integration true} test-helper-death-invalidates-native-identity
  (support/with-root (partial host-loss! verify-death!)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-held-descriptor-identity-survives-path-replacement
  (support/with-root replacement!))

(deftest ^{:stratum 2 :integration true} test-silent-helper-is-reaped-after-receipt-deadline
  (support/with-root (partial refused-program! fixtures/silent-program)))

(deftest ^{:stratum 2 :integration true} test-malformed-helper-receipt-is-refused-and-reaped
  (support/with-root (partial refused-program! fixtures/malformed-program)))

(deftest ^{:stratum 2 :integration true} test-non-directory-and-final-symlink-cannot-produce-a-pin
  (support/with-root refused-target!))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.descriptor-pin-integration-test))
