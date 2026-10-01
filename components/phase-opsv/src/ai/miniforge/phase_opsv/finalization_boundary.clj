;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-boundary
  "Evidence-only failure boundaries preserve mutation results and recovery state."
  (:require [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.finalization :as finalization]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.finalization-recovery :as recovery]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.finalization-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} restore-and-publish! [ctx]
  (flow/continue (runtime/ensure-assembly ctx) recovery/publish!))

(defn- ^{:stratum 0} call-with-exception-handling [output operation]
  (try (operation)
       (catch InterruptedException _
         (let [result (model/failure output :interrupted)] (.interrupt (Thread/currentThread)) result))
       (catch Error _ (assoc (model/failure output :fatal-publication-error) :anomaly/type :fatal))
       (catch Throwable _ (model/failure output :publication-exception))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finalize! [ctx output]
  (call-with-exception-handling output #(finalization/finalize! ctx output)))

(defn ^{:stratum 1} publish-finalized! [ctx]
  (call-with-exception-handling {} (partial restore-and-publish! ctx)))

(defn ^{:stratum 1} prepare [ctx]
  (call-with-exception-handling {} #(config/validate-context ctx)))
