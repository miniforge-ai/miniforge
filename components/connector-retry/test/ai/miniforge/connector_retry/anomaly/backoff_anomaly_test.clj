(ns ai.miniforge.connector-retry.anomaly.backoff-anomaly-test
  "Coverage for `backoff/compute-delay` boundary escalation via
   `response/throw-anomaly!`. Unknown retry strategy →
   `:anomalies/unsupported`."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-retry.backoff :as backoff])
  (:import (clojure.lang ExceptionInfo)))

(deftest compute-delay-unknown-strategy-throws-anomaly
  (testing "unknown :retry/strategy raises :anomalies/unsupported"
    (try
      (backoff/compute-delay {:retry/strategy :bogus
                              :retry/initial-delay-ms 100}
                             0)
      (is false "should have thrown")
      (catch ExceptionInfo e
        (is (re-find #"Unknown retry strategy" (.getMessage e)))
        (is (= :anomalies/unsupported (:anomaly/category (ex-data e))))
        (is (= :bogus (:strategy (ex-data e))))))))

(deftest compute-delay-known-strategies-return-numbers
  (testing "known strategies return non-negative delays"
    (is (= 100 (backoff/compute-delay {:retry/strategy :fixed
                                       :retry/initial-delay-ms 100}
                                      3)))
    (is (number? (backoff/compute-delay {:retry/strategy :exponential
                                         :retry/initial-delay-ms 100}
                                        2)))))
