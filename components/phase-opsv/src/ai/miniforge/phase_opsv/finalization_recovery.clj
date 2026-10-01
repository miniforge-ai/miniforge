;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-recovery
  "Publish retained successful actuation evidence without invoking its adapter."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.artifact-boundary :as artifacts]
            [ai.miniforge.phase-opsv.events :as events]
            [ai.miniforge.phase-opsv.finalization :as finalization]
            [ai.miniforge.phase-opsv.finalization-model :as model]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.post-actuation-checkpoint :as checkpoint]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-after-events! [ctx output]
  (let [published (events/emit-phase-events! ctx :opsv/actuate output)]
    (if (anomaly/anomaly? published) published (finalization/finalize! ctx output))))

(defn- ^{:stratum 0} retained-output [ctx]
  (let [output (:opsv/recovered-actuation-output ctx)]
    (cond
      (anomaly/anomaly? output) output
      (contains? (:execution/input ctx) :opsv/terminal-snapshot)
      (model/failure output :terminal-recovery-required)
      (not (instance? clojure.lang.IAtom (context/stream ctx)))
      (model/failure output :invalid-recovery-context)
      (map? (:opsv/actuation-record output)) output
      :else (model/failure {} :invalid-recovery-context))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} publish-restored! [ctx]
  (-> (retained-output ctx)
      (flow/continue (partial artifacts/publish-with-exception-handling ctx :opsv/actuate))
      (flow/continue (partial finalize-after-events! ctx))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [ctx]
  (if (= :finalized (:opsv.assembly/status (finalization/assembly ctx)))
    (finalization/publish-finalized! ctx)
    (flow/continue (checkpoint/restore ctx) publish-restored!)))

(comment
  (retained-output {}))
