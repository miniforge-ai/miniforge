;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.interface-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.runtime-signing.interface :as signing]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute FileAttribute PosixFilePermissions]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} grant
  {:grant/id #uuid "00000000-0000-0000-0000-000000000001"
   :grant/scope {:pr/repo "miniforge-ai/miniforge" :pr/number 7}
   :grant/expires-at (Instant/parse "2026-10-29T00:15:00Z")})

(defn- ^{:stratum 0} key-file []
  (io/file (.toFile (Files/createTempDirectory "signing" (into-array FileAttribute [])))
           "keys" "runtime-signing.key"))

(defn- ^{:stratum 0} mode [file]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath file)
                                  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-new-key-is-owner-only-and-stable-test
  (let [file (key-file)
        created (signing/load-or-create-key! file)]
    (is (= 32 (alength ^bytes created)))
    (is (= "rw-------" (mode file)) "created owner-only, not through the umask")
    (is (= (seq created) (seq (signing/load-or-create-key! file)))
        "an existing key is read back, never replaced")))

(deftest ^{:stratum 1} an-unsafe-key-file-is-refused-test
  (testing "given a key readable by other users → refused"
    (let [file (key-file)]
      (signing/load-or-create-key! file)
      (Files/setPosixFilePermissions (.toPath file) (PosixFilePermissions/fromString "rw-r--r--"))
      (is (anomaly/anomaly? (signing/load-or-create-key! file)))))
  (testing "given a symbolic link in place of the key → refused"
    (let [target (key-file)
          link (io/file (.getParentFile (.getParentFile target)) "linked.key")]
      (signing/load-or-create-key! target)
      (Files/createSymbolicLink (.toPath link) (.toPath target) (into-array FileAttribute []))
      (is (anomaly/anomaly? (signing/load-or-create-key! link)))))
  (testing "given a key directory other users can write → refused"
    (let [file (key-file)]
      (signing/load-or-create-key! file)
      (Files/setPosixFilePermissions (.toPath (.getParentFile file))
                                     (PosixFilePermissions/fromString "rwxrwxrwx"))
      (is (anomaly/anomaly? (signing/load-or-create-key! file)))))
  (testing "given malformed key text → refused"
    (let [file (key-file)]
      (signing/load-or-create-key! file)
      (spit file "not a key")
      (is (anomaly/anomaly? (signing/load-or-create-key! file))))))

(deftest ^{:stratum 1} printer-bindings-do-not-change-signatures-test
  (let [k (signing/load-or-create-key! (key-file))
        signature (signing/sign k grant)]
    (binding [*print-length* 0 *print-level* 1 *print-namespace-maps* true]
      (is (= signature (signing/sign k grant)))
      (is (not (signing/signed? k (assoc grant :grant/id (random-uuid)) signature))
          "a truncating printer must not make two grants sign alike"))))

(deftest ^{:stratum 1} signatures-bind-the-exact-value-test
  (let [k (signing/load-or-create-key! (key-file))
        signature (signing/sign k grant)]
    (is (signing/signed? k grant signature))
    (is (signing/signed? k (into {} (reverse (seq grant))) signature)
        "key order does not change the canonical value")
    (is (not (signing/signed? k (assoc-in grant [:grant/scope :pr/number] 8) signature)))
    (is (not (signing/signed? (signing/load-or-create-key! (key-file)) grant signature))
        "another runtime's key does not verify")
    (is (not (signing/signed? k grant nil)))
    (is (not (signing/signed? k grant (apply str (repeat 64 "0")))))))
