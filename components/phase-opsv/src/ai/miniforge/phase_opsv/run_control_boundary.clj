;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-boundary
  "Convert individual cleanup failures without skipping other active runs."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [type reason]
  (anomaly/anomaly type (msg/ts :control/unconfirmed) {:opsv/control-reason reason}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-exception-handling [operation]
  (try (operation)
       (catch InterruptedException _
         (let [result (failure :unavailable :interrupted)] (.interrupt (Thread/currentThread)) result))
       (catch Error _ (failure :fatal :fatal-cleanup-error))
       (catch Throwable _ (failure :unavailable :cleanup-exception))))

(defn- ^{:stratum 1} revoke! [run id now]
  (let [directory (:authority-directory run)
        result (grant/revoke-stored! directory id :revocation/operator now)
        current (if (anomaly/anomaly? result) (grant/current directory id) result)]
    (if (and (not (anomaly/any-anomaly? current)) (:grant/revoked-at current))
      {:grant/id id :revoked? true}
      (failure :unavailable :revocation-unconfirmed))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} revoke-with-exception-handling [run id now]
  (let [result (call-with-exception-handling #(revoke! run id now))]
    (if (anomaly/anomaly? result)
      (assoc-in result [:anomaly/data :grant/id] id)
      result)))

(defn ^{:stratum 2} abort-with-exception-handling [run]
  (call-with-exception-handling
   #(if (true? ((:request-abort! run))) true (failure :unavailable :abort-unconfirmed))))
