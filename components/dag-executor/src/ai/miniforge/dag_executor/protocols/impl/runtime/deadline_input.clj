;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-input
  "Confirm complete stdin delivery without waiting beyond the command deadline."
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} deliver! [^Process child payload]
  (try+
    (with-open [stream (.getOutputStream child)]
      (when payload (.write stream ^bytes payload))
      true)
    (catch Exception _ false)))

(defn- ^{:stratum 0} delivered? [worker until]
  (let [wait-ms (deadline/ceil-ms (deadline/remaining-ns until))]
    (true? (deref worker wait-ms ::pending))))

(defn- ^{:stratum 0} delivery-failure [until]
  (capture/failure (if (deadline/expired? until) :deadline :input-error)))

(defn ^{:stratum 0} cancel! [worker]
  (when worker (future-cancel worker)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} start! [child payload]
  (future (deliver! child payload)))

(defn ^{:stratum 1} confirm! [worker until reply]
  (cond
    (not (zero? (:exit reply))) reply
    (not (delivered? worker until)) (delivery-failure until)
    (deadline/expired? until) (capture/failure :deadline)
    :else reply))

(comment (delivery-failure (System/nanoTime)))
