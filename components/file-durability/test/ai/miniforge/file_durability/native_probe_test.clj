;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.native-probe-test
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]])
  (:import [java.io ByteArrayInputStream InputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} excessive-output-bytes
  "One byte beyond the process boundary's accepted payload."
  65537)

(def ^{:stratum 0 :private true} fixture-timeout
  "Shorten the probe deadline without comparing elapsed wall clocks."
  500)

(def ^{:stratum 0 :private true} slow-child ["/bin/sleep" "3"])

(def ^{:stratum 0 :private true} successful-child ["/usr/bin/true"])

(def ^{:stratum 0 :private true} failed-child ["/usr/bin/false"])

(defn- ^{:stratum 0} observe-launch [launch worker args options]
  (let [child (launch args options)]
    (reset! worker (:proc child))
    child))

(defn- ^{:stratum 0} delayed-capture [stopped ^InputStream input]
  (with-open [_stream input]
    (try
      (deref (promise) 3000 :unbounded)
      (catch InterruptedException _
        (deliver stopped :interrupted)))))

(deftest ^{:stratum 0} small-native-output-is-read-with-utf8
  (let [payload "clé"
        bytes (.getBytes payload StandardCharsets/UTF_8)]
    (is (= payload (probe/read-utf8 (ByteArrayInputStream. bytes))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} oversized-native-output-is-refused
  (is (nil? (probe/read-utf8 (ByteArrayInputStream. (byte-array excessive-output-bytes))))))

(deftest ^{:stratum 1} timed-out-child-is-terminated
  (when (fs/exists? (first slow-child))
    (let [launched (atom nil)
          launch process/process]
      (with-redefs-fn {#'probe/timeout-ms fixture-timeout
                       #'process/process (partial observe-launch launch launched)}
        #(is (nil? (probe/read-output slow-child nil))))
      (let [^Process worker @launched]
        (when (is (.waitFor worker 1 TimeUnit/SECONDS))
          (is (not (zero? (.exitValue worker)))))))))

(deftest ^{:stratum 1} timed-out-capture-is-cancelled-after-successful-child
  (when (fs/exists? (first successful-child))
    (let [stopped (promise)]
      (with-redefs-fn {#'probe/timeout-ms fixture-timeout
                       #'probe/read-utf8 (partial delayed-capture stopped)}
        #(is (nil? (probe/read-output successful-child nil))))
      (is (= :interrupted (deref stopped 1000 nil))))))

(deftest ^{:stratum 1} completed-child-with-nonzero-exit-is-not-evidence
  (when (fs/exists? (first failed-child))
    (is (nil? (probe/read-output failed-child nil)))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.native-probe-test))
