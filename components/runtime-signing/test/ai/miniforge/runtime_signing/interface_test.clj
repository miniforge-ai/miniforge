;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.runtime-signing.interface-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.runtime-signing.interface :as signing]
   [ai.miniforge.runtime-signing.key-material :as material]
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

(def ^{:stratum 0} linear-time-budget-ns
  "Five seconds. Encoding 30 levels of nested map keys takes milliseconds;
   the earlier double encoding of each key took minutes."
  5000000000)

(def ^{:stratum 0} fifo-wait-ms
  "How long a key load may take on a FIFO before the test counts it as hung."
  5000)

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
  (testing "given a FIFO in place of the key → refused without blocking"
    (let [file (key-file)]
      (.mkdirs (.getParentFile file))
      (.waitFor (.start (ProcessBuilder. ["mkfifo" (str file)])))
      (is (anomaly/anomaly? (deref (future (signing/load-or-create-key! file)) fifo-wait-ms :hung)))))
  (testing "given malformed key text → refused"
    (let [file (key-file)]
      (signing/load-or-create-key! file)
      (spit file "not a key")
      (is (anomaly/anomaly? (signing/load-or-create-key! file)))))
  (testing "given a valid key followed by padding and garbage → refused"
    (let [file (key-file)]
      (signing/load-or-create-key! file)
      (spit file (str (apply str (repeat 64 "a")) (apply str (repeat 192 " ")) "garbage"))
      (is (anomaly/anomaly? (signing/load-or-create-key! file))))))

(deftest ^{:stratum 1} printer-bindings-do-not-change-signatures-test
  (let [k (signing/load-or-create-key! (key-file))
        signature (signing/sign k grant)]
    (binding [*print-length* 0 *print-level* 1 *print-namespace-maps* true
              *print-readably* false]
      (is (= signature (signing/sign k grant)))
      (is (not (signing/signed? k (assoc grant :grant/id (random-uuid)) signature))
          "a truncating printer must not make two grants sign alike")
      (is (not= (signing/sign k {:a "x, :b y"}) (signing/sign k {:a "x" :b "y"}))
          "unquoted strings must not make two maps sign alike"))))

(deftest ^{:stratum 1} distinct-values-never-share-a-signature-test
  (let [k (signing/load-or-create-key! (key-file))
        at (Instant/parse "2026-10-29T00:00:00Z")]
    (is (not= (signing/sign k #{1 1.0}) (signing/sign k #{1})))
    (is (not= (signing/sign k [1]) (signing/sign k [1.0])))
    (is (not= (signing/sign k {:x nil}) (signing/sign k {:x (symbol "nil")})))
    (is (not= (signing/sign k [(symbol "a b")]) (signing/sign k ['a 'b])))
    (is (not= (signing/sign k [:a/b]) (signing/sign k [(keyword nil "a/b")])))
    (is (thrown? Exception (signing/sign k {:x (str (char 0xd800))}))
        "malformed text is refused, not replaced with ?")
    (is (false? (signing/signed? k {:x (str (char 0xd800))} (signing/sign k {:x "?"}))))
    (let [nested (reduce (fn [inner _] {inner 1}) {:leaf 1} (range 30))
          started (System/nanoTime)]
      (is (string? (signing/sign k nested)))
      (is (< (- (System/nanoTime) started) linear-time-budget-ns)
          "map keys nested 30 deep sign in linear, not exponential, time"))
    (let [deep (reduce (fn [inner _] [inner]) 1 (range 100000))]
      (is (thrown? IllegalArgumentException (signing/sign k deep)))
      (is (false? (signing/signed? k deep (apply str (repeat 64 "0"))))
          "deeply nested untrusted input is refused, not a stack overflow"))
    (is (thrown? IllegalArgumentException
                 (signing/sign k #{at (java.util.Date/from at)}))
        "entries that would encode alike are refused, not collapsed")
    (is (false? (signing/signed? k #{at (java.util.Date/from at)} (apply str (repeat 64 "0")))))))

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

(deftest ^{:stratum 1} a-failed-owner-probe-is-not-remembered-test
  (with-redefs [material/known-owner (atom nil)]
    (let [dir (.getParent (.toPath (key-file)))]
      (is (thrown? Exception (material/process-owner dir))
          "no key directory yet, so the probe cannot be written")
      (Files/createDirectories dir (into-array FileAttribute []))
      (is (= (Files/getOwner dir (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
             (material/process-owner dir))
          "a later probe in a writable directory still finds the owner"))))
