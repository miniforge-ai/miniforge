;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.event-stream.workflow-terminal-fields
  "Shared terminal payload projection; publication identity stays with the envelope."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.messages :as msg]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} attach [event payload]
  (if (anomaly/any-anomaly? event) event (merge payload event)))

(defn ^{:stratum 0} completed [status duration-ms opts]
  (cond-> {:message (msg/t :workflow/completed {:status (name status)})
           :workflow/status status}
    duration-ms (assoc :workflow/duration-ms duration-ms)
    (:tokens opts) (assoc :workflow/tokens (:tokens opts))
    (:cost-usd opts) (assoc :workflow/cost-usd (:cost-usd opts))
    (:pr-info opts) (assoc :workflow/pr-info (:pr-info opts))
    (seq (:pr-infos opts)) (assoc :workflow/pr-infos (:pr-infos opts))
    (:workflow/evidence-bundle-id opts)
    (assoc :workflow/evidence-bundle-id (:workflow/evidence-bundle-id opts))))

(defn- ^{:stratum 0} error-anomaly [error]
  (cond
    (anomaly/any-anomaly? error) error
    (instance? Throwable error) (response/from-exception error)
    (and (map? error) (:anomaly error)) (:anomaly error)
    :else nil))

(defn- ^{:stratum 0} anomaly-data [error]
  (let [data (response/anomaly->event-data error)
        code (:anomaly-code data)
        lookup-code (if (and (keyword? code) (nil? (namespace code)))
                      (keyword "anomalies" (name code)) code)]
    (assoc data :retryable? (response/retryable? lookup-code))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} error-details [error]
  (cond
    (instance? Throwable error) {:message (ex-message error) :type (str (type error))}
    (anomaly/any-anomaly? error) (anomaly-data error)
    :else error))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} failed [error failure-class]
  (let [details (error-details error)
        fallback (msg/t :workflow/unknown-error)
        event-data (anomaly-data
                    (or (error-anomaly error)
                        (response/make-anomaly :anomalies/fault (or (:message details) fallback))))
        message (or (:message details) (:message event-data) fallback)]
    (cond-> {:message (msg/t :workflow/failed {:reason message})
             :workflow/failure-reason message
             :workflow/error-details details
             :workflow/anomaly-code (:anomaly-code event-data)
             :workflow/retryable? (get event-data :retryable? false)}
      failure-class (assoc :failure/class failure-class))))

(comment ::workflow-terminal-projection)
