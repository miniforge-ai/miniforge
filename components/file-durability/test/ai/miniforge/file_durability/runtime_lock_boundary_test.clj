;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.runtime-lock-boundary-test
  (:require [ai.miniforge.file-durability.boundary.lock-peer]
            [ai.miniforge.file-durability.boundary.lock-peer-session :as session]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.runtime-peer-fixtures :as fixtures]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject-io! [& _] (throw (AssertionError.)))

(defn- ^{:stratum 0} host-peer [root]
  {:peer/executable root
   :peer/classpath (str root)})

(defn- ^{:stratum 0} invalid? [root peer limits]
  (= :invalid-input (:anomaly/type (durability/verify-runtime-locks! root peer limits))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} record-rejected-read! [reads & _]
  (swap! reads inc)
  (reject-io!))

(deftest ^{:stratum 1} test-lock-bounds-must-be-finite-positive-integer-deployment-data
  (let [root (fs/file (fs/absolutize "."))
        peer (host-peer root)]
    (with-redefs [directory/existing! reject-io!]
      (doseq [key (keys fixtures/limits)
              value [nil false 0 -1 1.5 (inc Integer/MAX_VALUE)]]
        (is (invalid? root peer (assoc fixtures/limits key value))))
      (is (invalid? root peer (assoc fixtures/limits :unexpected/key true)))
      (is (invalid? nil peer fixtures/limits)))))

(deftest ^{:stratum 1} test-peer-executable-and-every-classpath-entry-must-be-absolute
  (let [root (fs/file (fs/absolutize "."))
        peer (host-peer root)]
    (with-redefs [directory/existing! reject-io!]
      (doseq [[key value] [[:peer/executable (fs/file "bb")]
                           [:peer/executable "bb"]
                           [:peer/executable nil]
                           [:peer/classpath "."]
                           [:peer/classpath nil]
                           [:peer/classpath ""]]]
        (is (invalid? root (assoc peer key value) fixtures/limits))))))

(deftest ^{:stratum 1} test-peer-entrypoint-refuses-malformed-config-or-extra-arguments-before-io
  (let [complete? (ns-resolve 'ai.miniforge.file-durability.boundary.lock-peer 'complete-arguments?)]
    (with-redefs [session/completed? reject-io!]
      (doseq [arguments [[] ["jvm" "/root"] ["jvm" "/root" "nil"]
                         ["jvm" "/root" "{}"] ["jvm" "/root" "#=(identity 1)"]
                         ["jvm" "/root" (pr-str fixtures/limits) "extra"]]]
        (is (false? (complete? arguments)))))))

(deftest ^{:stratum 1} test-cancelled-host-proof-never-reads-the-root-or-clears-interruption
  (let [root (fs/file (fs/absolutize "."))]
    (with-redefs [directory/existing! reject-io!]
      (try+
        (.interrupt (Thread/currentThread))
        (let [reply (durability/verify-runtime-locks! root (host-peer root) fixtures/limits)
              interrupted? (Thread/interrupted)]
          (is (= :unavailable (:anomaly/type reply)))
          (is interrupted?))
        (finally (Thread/interrupted))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-wrong-runtime-identity-refuses-before-any-filesystem-io
  (let [reads (atom 0)
        read! (partial record-rejected-read! reads)]
    (with-redefs [directory/existing! read!]
      (is (false? (session/completed? "unsupported" "." fixtures/limits)))
      (is (zero? @reads)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.runtime-lock-boundary-test))
