;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-permissions-test
  (:require [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.workflow.checkpoint-temporary-support :as support]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private shared-permissions "rw-r-----")

(defn- ^{:stratum 0} verify-symlink-replacement [root]
  (let [original (fs/path root "original.edn")
        target (fs/path root "snapshot.edn")]
    (support/write! original :original)
    (fs/create-sym-link target original)
    (support/write! target :replacement)
    (is (not (fs/sym-link? target)))
    (is (= permissions/private-permissions (support/permission-mode target)))
    (is (= :replacement (edn/read-string (slurp (fs/file target)))))
    (is (= :original (edn/read-string (slurp (fs/file original)))))))

(defn- ^{:stratum 0} verify-permission-failure [root]
  (let [target (fs/path root "snapshot.edn")
        failure (IOException. "injected permission failure")]
    (support/write! target :original)
    (with-redefs [fs/set-posix-file-permissions (fn [& _] (throw failure))]
      (is (identical? failure (support/attempt-write! target :replacement))))
    (is (= :original (edn/read-string (slurp (fs/file target)))))
    (is (= ["snapshot.edn"] (mapv fs/file-name (fs/list-dir root))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} permission-failure-preserves-the-previous-checkpoint
  (fixture/call-with-temp-checkpoint-root
   (fn [root] (when (permissions/posix? root) (verify-permission-failure root)))))

(deftest ^{:stratum 1} symlink-replacement-does-not-copy-the-links-permissions
  (fixture/call-with-temp-checkpoint-root
   (fn [root] (when (permissions/posix? root) (verify-symlink-replacement root)))))

(defn- ^{:stratum 1} verify-permissions [root]
  (let [target (fs/path root "snapshot.edn")
        observed (atom [])
        write! (partial support/observe-permissions! durability/write-temporary-bytes! observed)]
    (with-redefs [durability/write-temporary-bytes! write!]
      (support/write! target :original)
      (is (= permissions/private-permissions (support/permission-mode target)))
      (fs/set-posix-file-permissions target shared-permissions)
      (support/write! target :replacement))
    (is (= shared-permissions (support/permission-mode target)))
    (is (= :replacement (edn/read-string (slurp (fs/file target)))))
    (is (= [permissions/private-permissions permissions/private-permissions] @observed))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} posix-checkpoints-preserve-published-permissions
  (fixture/call-with-temp-checkpoint-root
   (fn [root] (when (permissions/posix? root) (verify-permissions root)))))

(comment ::checkpoint-permissions)
