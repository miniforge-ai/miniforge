;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-directory-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [ai.miniforge.workflow.boundary.checkpoint-file :as checkpoint-file]
            [ai.miniforge.workflow.checkpoint-directory-support :as directories]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-temporary-support :as support]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-new-directories [root]
  (let [directory (fs/path root "new" "nested")
        target (fs/path directory "snapshot.edn")]
    (support/write! target :complete)
    (is (= directories/private-directory (support/permission-mode directory)))
    (is (= directories/private-directory (support/permission-mode (fs/parent directory))))
    (is (= :complete (directories/read-value target)))
    (fs/set-posix-file-permissions directory directories/shared-directory)
    (support/write! target :replacement)
    (is (= directories/shared-directory (support/permission-mode directory)))
    (is (= :replacement (directories/read-value target)))))

(defn- ^{:stratum 0} verify-unsafe-ancestor [root]
  (let [ancestor (fs/path root "shared")
        directory (fs/path ancestor "private")
        target (fs/path directory "snapshot.edn")]
    (support/write! target :original)
    (fs/set-posix-file-permissions ancestor (last directories/unsafe-directories))
    (is (instance? IOException (support/attempt-write! target :replacement)))
    (is (= :original (directories/read-value target)))
    (is (= directories/private-directory (support/permission-mode directory)))))

(defn- ^{:stratum 0} verify-resolved-directory [root]
  (let [source (fs/create-dir (fs/path root "source"))
        replacement (fs/create-dir (fs/path root "alternate"))
        alias (fs/create-sym-link (fs/path root "alias") source)
        write! (partial directories/switch-alias! checkpoint-file/fill! alias replacement)
        published (with-redefs [checkpoint-file/fill! write!]
                    (support/write! (fs/path alias "snapshot.edn") :complete))]
    (is (= (fs/path (fs/real-path source) "snapshot.edn") (fs/path published)))
    (is (= :complete (directories/read-value (fs/path source "snapshot.edn"))))
    (is (empty? (fs/list-dir replacement)))))

(defn- ^{:stratum 0} verify-untrusted-owner [root]
  (let [attributes (assoc (fs/read-attributes root "posix:*") :owner "untrusted-account")]
    (with-redefs [fs/read-attributes (constantly attributes)]
      (is (thrown? IOException (policy/check! root true))))))

(defn- ^{:stratum 0} verify-lock-directories [root]
  (let [directory (fs/path root "lock-root")
        locks (fs/path directory checkpoint-lock/locks-directory-name)
        id (random-uuid)]
    (is (= :entered (checkpoint-lock/with-run-lock directory id (constantly :entered))))
    (is (= directories/private-directory (support/permission-mode directory)))
    (is (= directories/private-directory (support/permission-mode locks)))
    (fs/set-posix-file-permissions locks (last directories/unsafe-directories))
    (is (anomaly/anomaly? (checkpoint-lock/with-run-lock directory id (constantly :entered))))))

(defn- ^{:stratum 0} verify-pin-directory [root]
  (let [id (fixture/expired-run! root)
        directory (fs/path root (str id))
        mode (last directories/unsafe-directories)]
    (fs/set-posix-file-permissions directory mode)
    (is (anomaly/anomaly? (store/pin-checkpoint! id {:checkpoint/root root})))
    (is (= mode (support/permission-mode directory)))
    (is (not (fs/exists? (fs/path directory "pinned"))))))

(defn- ^{:stratum 0} verify-poll-refusal [root id]
  (let [result (checkpoint-lock/with-run-lock root id (constantly :entered))]
    (is (anomaly/anomaly? result))
    (is (= directories/lock-poll-failure (get-in result [:anomaly/data :anomaly/ex-message])))))

(deftest ^{:stratum 0} writable-directories-cannot-replace-checkpoints
  (doseq [mode directories/unsafe-directories]
    (directories/call-with-posix-root #(directories/verify-unsafe-directory % mode))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-lock-failure [root]
  (let [id (random-uuid)
        observed (atom nil)
        fail! (partial directories/fail-lock-poll! observed)]
    (with-redefs-fn {#'checkpoint-lock/poll-lock fail!} (partial verify-poll-refusal root id))
    (is (false? (.isOpen ^java.nio.channels.FileChannel @observed)))
    (is (= :entered (checkpoint-lock/with-run-lock root id (constantly :entered))))))

(deftest ^{:stratum 1} private-creation-preserves-existing-read-only-sharing
  (directories/call-with-posix-root verify-new-directories))

(deftest ^{:stratum 1} writable-ancestors-cannot-replace-checkpoints
  (directories/call-with-posix-root verify-unsafe-ancestor))

(deftest ^{:stratum 1} changing-a-directory-alias-cannot-redirect-publication
  (directories/call-with-posix-root verify-resolved-directory))

(deftest ^{:stratum 1} untrusted-directory-owners-are-refused
  (directories/call-with-posix-root verify-untrusted-owner))

(deftest ^{:stratum 1} lock-directories-use-the-checkpoint-permission-policy
  (directories/call-with-posix-root verify-lock-directories))

(deftest ^{:stratum 1} pin-markers-refuse-unsafe-run-directories
  (directories/call-with-posix-root verify-pin-directory))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} lock-poll-failures-are-anomalies-and-release-the-channel
  (directories/call-with-posix-root verify-lock-failure))

(comment ::checkpoint-directory-policy)
