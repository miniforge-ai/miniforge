;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-publication-callback-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.runner-callbacks :as callbacks]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observe [calls action result & _args]
  (swap! calls conj action)
  result)

(defn- ^{:stratum 0} context [] {:execution/id (random-uuid)})

(def ^{:stratum 0} interceptor {:config {:phase :plan :agent :planner}})

(def ^{:stratum 0} phase-result {:status :success})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} notifications [entry-receipt completion-receipt]
  (let [calls (atom [])
        ctx (context)
        wrapped (callbacks/wrap-phase-callbacks
                  nil {:on-phase-start (partial observe calls :caller-start :started)
                       :on-phase-complete (partial observe calls :caller-complete :completed)})]
    (with-redefs [publishers/publish-phase-started! (partial observe calls :publish-start entry-receipt)
                  publishers/publish-phase-completed! (partial observe calls :publish-complete completion-receipt)
                  publishers/start-phase-heartbeat! (partial observe calls :heartbeat-start :handle)
                  publishers/stop-phase-heartbeat! (partial observe calls :heartbeat-stop nil)]
      (let [started ((:on-phase-start wrapped) ctx interceptor)
            completed (when-not (anomaly/any-anomaly? started)
                        ((:on-phase-complete wrapped) ctx interceptor phase-result))]
        ((:stop-phase-heartbeats! wrapped))
        {:start started :complete completed :calls @calls}))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} refused-entry-prevents-heartbeat-and-user-notifications
  (doseq [failure [(anomaly/anomaly :unavailable "journal unavailable" {})
                   (response/make-anomaly :anomalies/unavailable "journal unavailable")]]
    (let [result (notifications failure nil)]
      (is (= failure (:start result)))
      (is (nil? (:complete result)))
      (is (= [:publish-start] (:calls result))))))

(deftest ^{:stratum 2} refused-completion-stops-heartbeat-without-notifying-user
  (let [failure (anomaly/anomaly :unavailable "journal unavailable" {})
        result (notifications nil failure)]
    (is (= :started (:start result)))
    (is (= failure (:complete result)))
    (is (= [:publish-start :heartbeat-start :caller-start :heartbeat-stop :publish-complete]
           (:calls result)))))

(deftest ^{:stratum 2} accepted-publication-preserves-observer-order-and-results
  (doseq [receipt [nil {:event/type :workflow/phase-started}]]
    (let [result (notifications receipt receipt)]
      (is (= :started (:start result)))
      (is (= :completed (:complete result)))
      (is (= [:publish-start :heartbeat-start :caller-start
               :heartbeat-stop :publish-complete :caller-complete]
             (:calls result))))))
