;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.runtime.deadline-exchange
  "Own input and output workers for one local command lifecycle."
  (:refer-clojure :exclude [run!])
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-input :as input]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} cleanup! [child streams writer]
  (try+
    (capture/cleanup! child streams)
    (finally (input/cancel! writer))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run! [child until payload]
  (let [streams (volatile! [])
        writer (volatile! nil)]
    (try+
      (capture/start-drains! child streams)
      (vreset! writer (input/start! child payload))
      (let [reply (capture/await! child @streams until)]
        (input/confirm! @writer until reply))
      (finally (cleanup! child @streams @writer)))))

(comment (capture/failure :input-error))
