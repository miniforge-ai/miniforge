;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-boundary
  "Convert filesystem and codec failures at the artifact publication boundary."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [type key id]
  (anomaly/anomaly type (msg/t key) {:artifact/id id}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call-with-exception-handling
  ([id operation] (call-with-exception-handling id :unavailable :publication/unconfirmed operation))
  ([id type key operation]
   (try (operation)
        (catch InterruptedException _
          (let [result (failure type key id)] (.interrupt (Thread/currentThread)) result))
        (catch Error _ (failure :fatal :publication/fatal id))
        (catch Throwable _ (failure type key id)))))
