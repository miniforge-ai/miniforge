;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-confirmation
  "Publish one immutable artifact and attach only its acknowledged reference."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [output]
  (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed)
                   {:opsv/phase-output (model/confirmed-output output)}))

(defn- ^{:stratum 0} acknowledge! [ctx id]
  (evidence/accumulate-opsv-evidence! (:opsv/evidence-assembly-store ctx)
    (get-in ctx [:execution/input :opsv/evidence-bundle-id]) {:opsv/artifact-refs [id]}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} attach-acknowledged! [ctx output published]
  (let [id (:artifact/id published)
        assembly (acknowledge! ctx id)]
    (if (contains? (:opsv/artifact-refs assembly) id)
      (model/attach output published)
      (assoc-in (failure output) [:anomaly/data :artifact/id] id))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish-with-exception-handling [ctx value output]
  (try
    (let [directory (get-in ctx [:execution/opts :opsv/artifact-directory])
          published (artifact/publish! directory value)]
      (if (anomaly/anomaly? published)
        (assoc-in published [:anomaly/data :opsv/phase-output] output)
        (attach-acknowledged! ctx output published)))
    (catch InterruptedException _
      (let [result (failure output)] (.interrupt (Thread/currentThread)) result))
    (catch Error _ (assoc (failure output) :anomaly/type :fatal))
    (catch Throwable _ (failure output))))

(comment
  (failure {}))
