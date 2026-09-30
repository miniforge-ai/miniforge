;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.reliability.safe-mode-boundary
  "Retain failures from synchronous host quiescence without skipping safe-mode entry."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.reliability.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failure [type]
  (anomaly/anomaly type (messages/t :safe-mode/stop-unconfirmed) {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} stop-with-exception-handling [callback signal]
  (when callback
    (try (callback signal)
         (catch InterruptedException _
           (let [result (failure :unavailable)] (.interrupt (Thread/currentThread)) result))
         (catch Error _ (failure :fatal))
         (catch Throwable _ (failure :unavailable)))))
