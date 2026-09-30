;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifacts
  "Confirm phase artifacts and correlate only acknowledged durable references."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-confirmation :as confirmation]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} failure confirmation/failure)

(defn- ^{:stratum 0} directory-ready? [ctx]
  (not (anomaly/any-anomaly?
         (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory])
           (get-in ctx [:execution/input :opsv/evidence-bundle-id])))))

(defn- ^{:stratum 0} ready? [ctx]
  (let [assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                           (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (and (= :assembling (:opsv.assembly/status assembly))
         (= (context/workflow-id ctx) (:evidence-bundle/workflow-id assembly)))))

(defn- ^{:stratum 0} continue-publication [ctx output value]
  (flow/continue output (partial confirmation/publish-with-exception-handling ctx value)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare [ctx]
  (if (or (not (contains? (:execution/opts ctx) :opsv/artifact-directory))
          (and (ready? ctx) (directory-ready? ctx)))
    ctx
    (failure {})))

(defn ^{:stratum 1} publish! [ctx phase-key output]
  (let [confirmed (model/confirmed-output output)]
    (if-not (ready? ctx)
      (failure confirmed)
      (reduce (partial continue-publication ctx) confirmed (model/records ctx phase-key confirmed)))))

(comment
  (publish! {} :opsv/plan {}))
