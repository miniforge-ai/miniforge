;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-preparation-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.boundary.kubernetes-client :as client]
            [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unexpected-capture [calls _settings _declaration]
  (swap! calls inc)
  (anomaly/anomaly :unavailable "injected capture refusal" {}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-preparation-is-refused-without-acquisition-or-realizing-input-test
  (let [calls (atom 0)
        deferred (lazy-seq (throw (ex-info "must not realize request" {})))]
    (with-redefs [client/observe-verification! (partial unexpected-capture calls)]
      (doseq [value [nil (Object.) deferred]]
        (let [result (phase/prepare-kubernetes-verification! {} nil value)]
          (is (= :invalid-input (:anomaly/type result)))
          (is (= {} (:anomaly/data result)))))
      (is (zero? @calls))
      (is (not (realized? deferred))))))

(comment (clojure.test/run-tests 'ai.miniforge.phase-opsv.kubernetes-preparation-test))
