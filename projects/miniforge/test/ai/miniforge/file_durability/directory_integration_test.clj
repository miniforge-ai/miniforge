;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.directory-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} private-mode
  "New protected directories permit access only to their owner."
  "rwx------")

(def ^{:stratum 0 :private true} shared-mode
  "Existing read-only sharing remains safe and must not be silently removed."
  "rwxr-xr-x")

(def ^{:stratum 0 :private true} unsafe-mode
  "A writable non-sticky ancestor permits entry replacement and must be refused."
  "rwxrwxrwx")

(defn- ^{:stratum 0} mode [path] (fs/posix->str (fs/posix-file-permissions path)))

(defn- ^{:stratum 0} native-permissions [& _] (throw (UnsupportedOperationException.)))

(defn- ^{:stratum 0} unreadable-permissions [& _] (throw (IOException.)))

(defn- ^{:stratum 0} interrupted-permissions [& _] (throw (InterruptedException.)))

(defn- ^{:stratum 0} call-with-root [verify!]
  (let [root (fs/create-temp-dir)]
    (try
      (when (= :posix (durability/filesystem-profile (fs/file root))) (verify! root))
      (finally (fs/delete-tree root)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-protected-creation [root]
  (let [nested (fs/path root "new" "nested")
        file (fs/file nested)
        prepared (durability/prepare-protected-directory! file)]
    (is (= (fs/real-path nested) prepared (durability/resolve-protected-directory file)))
    (is (= private-mode (mode prepared) (mode (fs/parent prepared))))
    (fs/set-posix-file-permissions nested shared-mode)
    (is (= prepared (durability/prepare-protected-directory! file)))
    (is (= shared-mode (mode nested)))))

(defn- ^{:stratum 1} verify-unsafe-ancestry [root]
  (let [unsafe (fs/create-dir (fs/path root "unsafe"))
        target (fs/create-dir (fs/path root "target"))
        _hop (fs/create-sym-link (fs/path unsafe "hop") target)
        alias (fs/create-sym-link (fs/path root "alias") (fs/path "unsafe" "hop"))]
    (fs/set-posix-file-permissions unsafe unsafe-mode)
    (is (anomaly/anomaly? (durability/prepare-protected-directory! (fs/file alias "new"))))
    (is (anomaly/anomaly? (durability/resolve-protected-directory (fs/file alias))))
    (is (= unsafe-mode (mode unsafe)))
    (is (empty? (fs/list-dir target)))))

(defn- ^{:stratum 1} verify-native-profile [root]
  (let [target (fs/create-dir (fs/path root "target"))
        alias (fs/create-sym-link (fs/path root "alias") target)
        regular (fs/create-temp-file {:dir root})]
    (with-redefs [fs/posix-file-permissions native-permissions]
      (is (= :native-defaults (durability/filesystem-profile (fs/file root))))
      (is (= (fs/path (fs/real-path target) "new")
             (durability/prepare-protected-directory! (fs/file alias "new"))))
      (is (anomaly/anomaly? (durability/resolve-protected-directory (fs/file regular)))))
    (with-redefs [fs/posix-file-permissions unreadable-permissions]
      (is (anomaly/anomaly? (durability/filesystem-profile (fs/file root)))))
    (is (anomaly/anomaly? (durability/resolve-protected-directory (fs/file root "absent"))))))

(defn- ^{:stratum 1} verify-interruption [root]
  (try
    (with-redefs [fs/posix-file-permissions interrupted-permissions]
      (let [result (durability/filesystem-profile (fs/file root))
            interrupted? (Thread/interrupted)]
        (is (anomaly/anomaly? result))
        (is interrupted?)))
    (finally (Thread/interrupted))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} protected-creation-retains-existing-sharing (call-with-root verify-protected-creation))

(deftest ^{:stratum 2} unsafe-link-ancestry-is-refused-without-mutation (call-with-root verify-unsafe-ancestry))

(deftest ^{:stratum 2} native-defaults-and-read-failures-are-distinct (call-with-root verify-native-profile))

(deftest ^{:stratum 2} capability-probe-preserves-interruption (call-with-root verify-interruption))

(comment ::protected-storage-boundary)
