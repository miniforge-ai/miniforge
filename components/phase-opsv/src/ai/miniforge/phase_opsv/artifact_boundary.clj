;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-boundary
  "Preserve completed phase output when artifact publication cannot be confirmed."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifacts :as artifacts]
            [ai.miniforge.phase-opsv.artifact-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prepare artifacts/prepare)

(defn- ^{:stratum 0} publish-prepared [ctx phase-key output]
  (try (artifacts/publish! ctx phase-key output)
       (catch InterruptedException _
         (let [result (artifacts/failure output)] (.interrupt (Thread/currentThread)) result))
       (catch Error _ (assoc (artifacts/failure output) :anomaly/type :fatal))
       (catch Throwable _ (artifacts/failure output))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} publish-with-exception-handling [ctx phase-key output]
  (let [cleared (model/without-attempt-receipts output)]
    (if (or (anomaly/any-anomaly? cleared)
            (not (contains? (:execution/opts ctx) :opsv/artifact-directory)))
      cleared
      (publish-prepared ctx phase-key (model/begin-publication cleared)))))
