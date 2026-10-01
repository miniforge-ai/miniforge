;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.interface-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.interface :as durability]
            [ai.miniforge.file-durability.io :as file-io]
            [clojure.test :refer [deftest is]])
  (:import [java.io File IOException]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} io-failure [& _] (throw (IOException.)))

(defn- ^{:stratum 0} interrupted [& _] (throw (InterruptedException.)))

(defn- ^{:stratum 0} fatal [& _] (throw (AssertionError.)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} filesystem-failures-are-data-at-every-public-operation
  (let [file (File. "unused")]
    (with-redefs [file-io/write-new-text! io-failure
                  file-io/write-temporary-bytes! io-failure
                  file-io/sync-ancestry! io-failure
                  file-io/confirm! io-failure]
      (doseq [result [(durability/write-new-text! file "")
                      (durability/write-temporary-bytes! file (byte-array 0))
                      (durability/sync-ancestry! file)
                      (durability/confirm! file)]]
        (is (anomaly/anomaly? result))
        (is (= "unused" (get-in result [:anomaly/data :durability/path])))))))

(deftest ^{:stratum 1} interruption-is-preserved-and-fatal-errors-propagate
  (let [file (File. "unused")]
    (try
      (with-redefs [file-io/confirm! interrupted]
        (let [result (durability/confirm! file)
              interrupted? (Thread/interrupted)]
          (is (anomaly/anomaly? result))
          (is interrupted?)))
      (finally (Thread/interrupted)))
    (with-redefs [file-io/confirm! fatal]
      (is (thrown? AssertionError (durability/confirm! file))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.file-durability.interface-test))
