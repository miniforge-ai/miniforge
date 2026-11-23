;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.publication-boundary
  "Convert publication exceptions while retaining the latest acknowledged output."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.finalization-model :as model]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} call-with-exception-handling [output operation]
  (try+ (operation)
       (catch anomaly/any-anomaly? failure
         (assoc-in failure [:anomaly/data :opsv/phase-output] output))
       (catch InterruptedException _
         (let [result (model/failure output :interrupted)] (.interrupt (Thread/currentThread)) result))
       (catch Error _ (assoc (model/failure output :fatal-publication-error) :anomaly/type :fatal))
       (catch Object _ (model/failure output :publication-exception))))
