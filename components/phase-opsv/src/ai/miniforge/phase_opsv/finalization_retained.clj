;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-retained
  "Reuse a published seal when recovery starts from a pre-publication checkpoint."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.finalization-model :as model]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} existing-bundle [ctx assembly]
  (let [directory (get-in ctx [:execution/opts :opsv/artifact-directory])
        published (artifact/read-published directory (:evidence-bundle/id assembly))
        bundle (:artifact/content published)]
    (cond
      (nil? published) nil
      (anomaly/any-anomaly? published) published
      (= published (model/bundle-artifact bundle)) bundle
      :else (model/failure {} :invalid-finalized-evidence))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} restore! [ctx assembly]
  (let [bundle (existing-bundle ctx assembly)]
    (cond
      (nil? bundle) nil
      (anomaly/any-anomaly? bundle) bundle
      :else (evidence/restore-finalized-opsv-bundle!
              (:opsv/evidence-assembly-store ctx) bundle (:opsv/artifact-refs assembly)))))

(comment
  ::restore!)
