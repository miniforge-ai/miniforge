;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization
  "Confirm persisted material, finalize N6 once, and publish its immutable bundle."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as material]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.finalization-model :as model]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} assembly [ctx]
  (when-let [store (:opsv/evidence-assembly-store ctx)]
    (evidence/get-opsv-assembly store (get-in ctx [:execution/input :opsv/evidence-bundle-id]))))

(defn- ^{:stratum 0} available? [ctx id]
  (let [record (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) id)]
    (and (map? record) (not (anomaly/any-anomaly? record))
         (= id (:artifact/id record))
         (= (context/workflow-id ctx) (get-in record [:artifact/metadata :workflow/id]))
         (= (get-in ctx [:execution/input :opsv/evidence-bundle-id])
            (get-in record [:artifact/metadata :opsv/evidence-bundle-id]))
         (= record (material/record ctx {::content (:artifact/content record)}
                                    [(get-in record [:artifact/metadata :opsv/material-kind])
                                     (:artifact/type record) ::content])))))

(defn- ^{:stratum 0} confirmed-output? [ctx output]
  (every? (fn [expected]
            (let [id (:artifact/id expected)]
              (and (= id (get-in output [:opsv/phase-artifact-ids
                                         (get-in expected [:artifact/metadata :opsv/material-kind])]))
                   (= expected (artifact/read-published
                                (get-in ctx [:execution/opts :opsv/artifact-directory]) id)))))
          (mapcat #(material/records ctx % output)
                  [:opsv/discover :opsv/execute :opsv/converge :opsv/verify :opsv/actuate])))

(defn- ^{:stratum 0} publish-bundle! [ctx output bundle]
  (if (anomaly/any-anomaly? bundle)
    (model/failure output :invalid-evidence)
    (let [expected (model/bundle-artifact bundle)
          published (artifact/publish! (get-in ctx [:execution/opts :opsv/artifact-directory]) expected)]
      (if (= expected published)
        (assoc output :opsv/evidence-bundle bundle :opsv/evidence-artifact-id (:artifact/id published))
        (assoc-in (model/failure output :bundle-publication)
                  [:anomaly/data :opsv/evidence-bundle] bundle)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} finalize! [ctx output]
  (let [record (assembly ctx)
        refs (:opsv/artifact-refs record)]
    (if-not (and (= :assembling (:opsv.assembly/status record))
                 (every? (partial available? ctx) refs)
                 (confirmed-output? ctx output))
      (model/failure output :unavailable-material)
      (publish-bundle! ctx output
        (evidence/finalize-opsv-evidence!
         (:opsv/evidence-assembly-store ctx) (:evidence-bundle/id record)
         (config/base-bundle ctx output) (model/evidence-section record output) refs)))))

(defn ^{:stratum 1} publish-finalized! [ctx]
  (let [record (assembly ctx)
        bundle (:opsv.assembly/bundle record)
        validation-store (evidence/restore-opsv-assembly-store
                          (dissoc (assoc record :opsv.assembly/status :assembling) :opsv.assembly/bundle))]
    (if-not (and (= :finalized (:opsv.assembly/status record))
                 (= (context/workflow-id ctx) (:evidence-bundle/workflow-id bundle))
                 (every? (partial available? ctx) (:opsv/artifact-refs record))
                 (= bundle (evidence/finalize-opsv-evidence!
                            validation-store (:evidence-bundle/id record) bundle
                            (:evidence/opsv bundle) (:opsv/artifact-refs record))))
      (model/failure {} :invalid-finalized-evidence)
      (publish-bundle! ctx {} bundle))))
