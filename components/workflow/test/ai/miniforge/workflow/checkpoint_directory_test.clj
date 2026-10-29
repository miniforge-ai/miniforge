;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-directory-test
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory-policy :as policy]
            [ai.miniforge.workflow.boundary.checkpoint-permissions :as permissions]
            [ai.miniforge.workflow.checkpoint-directory-support :as directories]
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

(defn- ^{:stratum 0} verify-pinned-directory [root]
  (let [source (fs/create-dir (fs/path root "source"))
        replacement (fs/create-dir (fs/path root "alternate"))
        alias (fs/create-sym-link (fs/path root "alias") source)
        write! (partial directories/switch-alias! spit alias replacement)]
    (with-redefs [spit write!]
      (support/write! (fs/path alias "snapshot.edn") :complete))
    (is (= :complete (directories/read-value (fs/path source "snapshot.edn"))))
    (is (empty? (fs/list-dir replacement)))))

(defn- ^{:stratum 0} verify-untrusted-owner [root]
  (let [attributes (assoc (fs/read-attributes root "posix:*") :owner "untrusted-account")]
    (with-redefs [fs/read-attributes (constantly attributes)]
      (is (thrown? IOException (policy/check! root true))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-directory-policy [root]
  (when (permissions/posix? root)
    (verify-new-directories root)
    (doseq [mode directories/unsafe-directories] (directories/verify-unsafe-directory root mode))
    (verify-pinned-directory root)
    (verify-untrusted-owner root)
    (verify-unsafe-ancestor root)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} protected-directories-preserve-checkpoints-and-read-only-sharing
  (fixture/call-with-temp-checkpoint-root verify-directory-policy))

(comment ::checkpoint-directory-policy)
