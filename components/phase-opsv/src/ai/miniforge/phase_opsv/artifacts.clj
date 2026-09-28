;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifacts
  "Confirm phase artifacts and correlate only acknowledged durable references."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as model]
            [ai.miniforge.phase-opsv.flow :as flow]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [output]
  (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed)
                   {:opsv/phase-output output}))

(defn- ^{:stratum 0} ready? [ctx]
  (let [assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store ctx)
                                           (get-in ctx [:execution/input :opsv/evidence-bundle-id]))]
    (and (= :assembling (:opsv.assembly/status assembly))
         (= (context/workflow-id ctx) (:evidence-bundle/workflow-id assembly)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} confirm! [ctx output value]
  (let [directory (get-in ctx [:execution/opts :opsv/artifact-directory])
        published (artifact/publish! directory value)]
    (if (anomaly/anomaly? published)
      (assoc-in published [:anomaly/data :opsv/phase-output] output)
      (let [id (:artifact/id published)
            assembly (evidence/accumulate-opsv-evidence! (:opsv/evidence-assembly-store ctx)
                        (get-in ctx [:execution/input :opsv/evidence-bundle-id]) {:opsv/artifact-refs [id]})]
        (if (contains? (:opsv/artifact-refs assembly) id)
          (model/attach output published)
          (assoc-in (failure output) [:anomaly/data :artifact/id] id))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [ctx phase-key output]
  (if-not (ready? ctx)
    (failure output)
    (reduce (fn [result value]
              (flow/continue result #(confirm! ctx % value)))
            output (model/records ctx phase-key output))))

(comment
  (publish! {} :opsv/plan {}))
