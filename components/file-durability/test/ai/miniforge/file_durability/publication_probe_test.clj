;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.publication-probe-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary.atomic-publication :as atomic]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.io :as file-io]
            [ai.miniforge.file-durability.publication-support :as support]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]
           [java.nio.file FileAlreadyExistsException Files]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail-sync! [_] (throw (IOException.)))

(defn- ^{:stratum 0} ignore-replacement! [_ destination] destination)

(defn- ^{:stratum 0} allow-collision! [link! candidate destination]
  (Files/deleteIfExists (.toPath ^java.io.File destination))
  (link! candidate destination))

(defn- ^{:stratum 0} destructive-collision! [link! candidate destination]
  (when (Files/deleteIfExists (.toPath ^java.io.File destination))
    (throw (FileAlreadyExistsException. (str destination))))
  (link! candidate destination))

(defn- ^{:stratum 0} probe-and-check-cleanup! [root]
  (let [result (durability/verify-publication! root)]
    (is (empty? (fs/list-dir root)))
    result))

(deftest ^{:stratum 0} missing-root-is-not-created
  (support/with-root
   (fn [root]
     (let [missing (.toFile (fs/path root (str (random-uuid))))]
       (is (anomaly/anomaly? (durability/verify-publication! missing)))
       (is (not (fs/exists? missing)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} forced-publication-uses-a-disposable-protected-directory
  (support/with-root
   (fn [root]
     (let [result (probe-and-check-cleanup! root)]
       (if (support/posix? root)
         (is (= :publication-verified result))
         (is (anomaly/anomaly? result)))))))

(deftest ^{:stratum 1} failed-directory-sync-cannot-verify-publication
  (support/with-root
   (fn [root]
     (with-redefs [file-io/sync-ancestry! fail-sync!]
       (is (anomaly/anomaly? (probe-and-check-cleanup! root)))))))

(deftest ^{:stratum 1} a-provider-that-retains-the-candidate-cannot-verify-publication
  (support/with-root
   (fn [root]
     (with-redefs [atomic/replace! ignore-replacement!]
       (is (anomaly/anomaly? (probe-and-check-cleanup! root)))))))

(deftest ^{:stratum 1} a-provider-that-allows-a-link-collision-cannot-verify-publication
  (support/with-root
   (fn [root]
     (with-redefs [atomic/link! (partial allow-collision! atomic/link!)]
       (is (anomaly/anomaly? (probe-and-check-cleanup! root)))))))

(deftest ^{:stratum 1} a-collision-must-preserve-the-existing-destination
  (support/with-root
   (fn [root]
     (with-redefs [atomic/link! (partial destructive-collision! atomic/link!)]
       (is (anomaly/anomaly? (probe-and-check-cleanup! root)))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.publication-probe-test))
