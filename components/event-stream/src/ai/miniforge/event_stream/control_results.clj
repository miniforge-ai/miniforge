;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.control-results
  "Preserve executor failures at the control invocation boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.messages :as messages]
            [ai.miniforge.response.interface :as response]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} denied [authorization]
  {:status :denied :reason (:reason authorization) :anomaly (:anomaly authorization)})

(defn- ^{:stratum 0} failure [message data]
  (response/failure (or message (messages/t :control/execution-failed)) {:data data}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} from-value [value]
  (cond
    (anomaly/any-anomaly? value)
    (failure (:anomaly/message value) value)

    (or (response/error? value) (response/success? value)
        (#{:failure :denied} (:status value))) value

    :else (response/success value)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} invoke!
  "Invoke the effect once; returned failures and thrown exceptions remain failures."
  [execution-fn action]
  (try+
    (from-value (execution-fn action))
    (catch map? value
      (failure (get value :anomaly/message (:message &throw-context)) value))
    (catch Exception e
      (when (instance? InterruptedException e)
        (.interrupt (Thread/currentThread)))
      (failure (ex-message e) (ex-data e)))))
