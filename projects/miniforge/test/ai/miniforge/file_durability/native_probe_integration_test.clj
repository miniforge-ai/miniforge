;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.native-probe-integration-test
  (:require [ai.miniforge.file-durability.boundary.native-probe :as probe]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]])
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

(def ^{:stratum 0 :private true} slow-child
  "Outlive the shortened probe budget unless forced termination runs."
  ["/bin/sleep" "3"])

(def ^{:stratum 0 :private true} successful-child
  "Exit successfully without producing native metadata."
  ["/usr/bin/true"])

(def ^{:stratum 0 :private true} failed-child
  "Fail without producing any acknowledgement."
  ["/usr/bin/false"])

(def ^{:stratum 0 :private true} capture-delay-ms
  "Keep the substituted capture pending beyond the probe and cancellation budgets."
  3000)

(def ^{:stratum 0 :private true} capture-cancellation-ms
  "Bound observation of the cancellation signal without sleeping the test thread."
  1000)

(def ^{:stratum 0 :private true} terminated-child-exit-ms
  "Allow one second to observe the OS exit after forced termination."
  1000)

(defn- ^{:stratum 0} observe-launch [launch worker args options]
  (let [child (launch args options)]
    (reset! worker (:proc child))
    child))

(deftest ^{:stratum 0} test-small-native-output-is-read-with-utf8
  (let [payload "clé"
        bytes (.getBytes payload StandardCharsets/UTF_8)]
    (is (= payload (probe/read-utf8 (ByteArrayInputStream. bytes))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} delayed-capture [stopped ^InputStream input]
  (with-open [_stream input]
    (try+
      (deref (promise) capture-delay-ms :unbounded)
      (catch InterruptedException _
        (deliver stopped :interrupted)))))

(deftest ^{:stratum 1} test-oversized-native-output-is-refused
  (is (nil? (probe/read-utf8 (ByteArrayInputStream. (byte-array excessive-output-bytes))))))

(deftest ^{:stratum 1 :integration true} test-timed-out-child-is-terminated
  (when (fs/exists? (first slow-child))
    (let [launched (atom nil)
          launch process/process]
      (with-redefs-fn {#'probe/timeout-ms fixture-timeout
                       #'process/process (partial observe-launch launch launched)}
        #(is (nil? (probe/read-output slow-child nil))))
      (let [^Process worker @launched]
        (when (is (.waitFor worker terminated-child-exit-ms TimeUnit/MILLISECONDS))
          (is (not (zero? (.exitValue worker)))))))))

(deftest ^{:stratum 1 :integration true} test-completed-child-with-nonzero-exit-is-not-evidence
  (when (fs/exists? (first failed-child))
    (is (nil? (probe/read-output failed-child nil)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-timed-out-capture-is-cancelled-after-successful-child
  (when (fs/exists? (first successful-child))
    (let [stopped (promise)]
      (with-redefs-fn {#'probe/timeout-ms fixture-timeout
                       #'probe/read-utf8 (partial delayed-capture stopped)}
        #(is (nil? (probe/read-output successful-child nil))))
      (is (= :interrupted (deref stopped capture-cancellation-ms nil))))))

(comment (clojure.test/run-tests 'ai.miniforge.file-durability.native-probe-integration-test))
