;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-acquisition
  "Compose legacy acquisition callbacks without claiming immutable Job ownership."
  (:require [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finish! [observe! cleanup! retain!]
  (let [observed (observe!)]
    (if (result/err? observed)
      (do (cleanup!) observed)
      (retain! (:data observed)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} acquire!
  "Preserve submit, observe, then cleanup-on-error or retain-on-success order."
  [submit! observe! cleanup! retain!]
  (result/and-then (submit!)
                   (fn [_] (finish! observe! cleanup! retain!))))

(comment
  (acquire! (constantly (result/ok :submitted))
            (constantly (result/ok :running))
            (constantly nil)
            result/ok))
