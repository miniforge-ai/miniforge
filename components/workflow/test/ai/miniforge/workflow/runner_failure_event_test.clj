;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-failure-event-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-failure-event :as facts]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} context [error]
  {:execution/current-phase :opsv/verify :execution/errors [error]})

(deftest ^{:stratum 0} missing-failure-facts-remain-explicitly-unknown
  (let [data (facts/data {})]
    (is (= :failure.class/unknown (get-in data [:options :failure/class])))
    (is (= (messages/t :status/failed) (get-in data [:error :message])))
    (is (not (contains? (:options data) :workflow/failure-phase)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} canonical-exception-provenance-determines-the-failure-class
  (let [cause (anomaly/exception-anomaly :fault "Socket stalled"
                                        (java.net.SocketTimeoutException. "Socket stalled"))
        data (facts/data (context cause))]
    (is (= :failure.class/timeout (get-in data [:options :failure/class])))
    (is (= cause (get-in data [:error :anomaly])))
    (is (= [cause] (get-in data [:error :errors])))))

(deftest ^{:stratum 1} canonical-and-legacy-causes-use-the-runtime-taxonomy
  (doseq [cause [(anomaly/anomaly :timeout "Connection refused" {})
                (response/make-anomaly :anomalies/timeout "Connection refused")]]
    (let [error {:message "Connection refused" :anomaly cause}
          data (facts/data (context error))]
      (is (= :failure.class/timeout (get-in data [:options :failure/class])))
      (is (= :opsv/verify (get-in data [:options :workflow/failure-phase])))
      (is (= [error] (get-in data [:error :errors])))
      (is (= cause (get-in data [:error :anomaly]))))))

(deftest ^{:stratum 1} native-phase-anomaly-supplies-the-missing-execution-error
  (let [cause (anomaly/anomaly :unavailable "Provider unavailable" {})
        ctx (-> (context nil)
                (assoc :execution/errors [])
                (assoc-in [:execution/phase-results :opsv/verify :result :output] cause))
        data (facts/data ctx)]
    (is (= :failure.class/external (get-in data [:options :failure/class])))
    (is (= (:anomaly/message cause) (get-in data [:error :message])))
    (is (= cause (get-in data [:error :anomaly])))))

(deftest ^{:stratum 1} latest-execution-error-takes-precedence-over-earlier-phase-failure
  (let [ctx (-> (context {:phase :opsv/actuate :message "Budget exhausted"})
                (assoc-in [:phase :result :output]
                          (anomaly/anomaly :timeout "Previous timeout" {})))
        data (facts/data ctx)]
    (is (= "Budget exhausted" (get-in data [:error :message])))
    (is (= :opsv/actuate (get-in data [:options :workflow/failure-phase])))
    (is (= :failure.class/resource (get-in data [:options :failure/class])))))
