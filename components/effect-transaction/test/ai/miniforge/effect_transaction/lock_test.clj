;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.lock-test
  "Runtime-portable ownership and release of the dedicated lock channel."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.effect-transaction.fixtures :as fixture]
            [ai.miniforge.effect-transaction.persistence :as persistence]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} lock-excludes-another-channel-and-releases-after-success-test
  (let [directory (fixture/tmp-dir) id (random-uuid)
        result (persistence/with-record-lock directory id
                 #(persistence/with-record-lock directory id (constantly :not-admitted)))]
    (is (anomaly/anomaly? result))
    (is (= :anomalies.effect-transaction/lock-conflict (:anomaly/subtype result)))
    (is (= :released (persistence/with-record-lock directory id (constantly :released))))))

(deftest ^{:stratum 0} lock-releases-without-masking-a-thrown-failure-test
  (doseq [failure [(ex-info "original operation failure" {}) (AssertionError. "original error")]]
    (let [directory (fixture/tmp-dir) id (random-uuid)
          observed (try (persistence/with-record-lock directory id #(throw failure))
                        (catch Throwable caught caught))]
      (is (identical? failure observed))
      (is (= :released (persistence/with-record-lock directory id (constantly :released)))))))
