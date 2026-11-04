;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization
  "Confirm persisted material, finalize N6 once, and publish its immutable bundle."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as material]
            [ai.miniforge.phase-opsv.event-evidence :as event-evidence]
            [ai.miniforge.phase-opsv.finalization-config :as config]
            [ai.miniforge.phase-opsv.finalization-model :as model]
            [ai.miniforge.phase-opsv.finalization-retained :as retained]
            [ai.miniforge.phase-opsv.publication-boundary :as publication]
            [ai.miniforge.phase-opsv.protected-material :as protected]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retained-or-finalized! [ctx record output]
  (let [bundle (retained/restore! ctx record)]
    (if (nil? bundle)
      (evidence/finalize-opsv-evidence!
        (:opsv/evidence-assembly-store ctx) (:evidence-bundle/id record)
        (config/base-bundle ctx output) (model/evidence-section record output) (:opsv/artifact-refs record))
      bundle)))

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
         (protected/valid? ctx record))))

(defn- ^{:stratum 0} confirmed-record? [ctx output refs [kind type content-key]]
  (let [id (get-in output [:opsv/phase-artifact-ids kind])
        directory (get-in ctx [:execution/opts :opsv/artifact-directory])
        stored (artifact/read-published directory id)
        expected-hash (artifact/content-digest (redaction/redact (get output content-key)))
        stored-hash (artifact/content-digest (:artifact/content stored))]
    (and (contains? refs id) (= id (:artifact/id stored))
         (= kind (get-in stored [:artifact/metadata :opsv/material-kind]))
         (= type (:artifact/type stored)) (protected/valid? ctx stored)
         (string? expected-hash) (= expected-hash stored-hash))))

(defn- ^{:stratum 0} publish-valid-bundle! [ctx output bundle]
  (let [expected (model/bundle-artifact bundle)
        directory (get-in ctx [:execution/opts :opsv/artifact-directory])
        published (artifact/publish! directory expected)]
    (if (= expected published)
      (model/published-output output bundle (:artifact/id published))
      (assoc-in (model/failure output :bundle-publication) [:anomaly/data :opsv/evidence-bundle] bundle))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} collected-assembly [ctx]
  (let [record (assembly ctx)]
    (if (= :assembling (:opsv.assembly/status record))
      (event-evidence/capture! (:opsv/evidence-assembly-store ctx) (:evidence-bundle/id record)
                               (context/stream ctx) (context/workflow-id ctx))
      record)))

(defn- ^{:stratum 1} confirmed-output? [ctx output refs]
  (let [materials (mapcat #(material/materials % output)
                       [:opsv/discover :opsv/execute :opsv/converge :opsv/verify :opsv/actuate])]
    (every? (partial confirmed-record? ctx output (set refs)) materials)))

(defn- ^{:stratum 1} publish-bundle! [ctx output bundle]
  (if (or (anomaly/any-anomaly? bundle)
          (not (:valid? (evidence/validate-published-bundle bundle))))
    (model/failure output :invalid-evidence)
    (publish-valid-bundle! ctx output bundle)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize! [ctx output]
  (let [record (collected-assembly ctx)
        refs (:opsv/artifact-refs record)]
    (if-not (and (= :assembling (:opsv.assembly/status record))
                 (every? (partial available? ctx) refs)
                 (confirmed-output? ctx output refs))
      (model/failure output :unavailable-material)
      (publish-bundle! ctx output (retained-or-finalized! ctx record output)))))

(defn ^{:stratum 2} publish-finalized! [ctx]
  (let [record (assembly ctx)
        bundle (:opsv.assembly/bundle record)
        output (material/begin-publication {})]
    (if-not (and (= :finalized (:opsv.assembly/status record))
                 (= (context/workflow-id ctx) (:evidence-bundle/workflow-id bundle))
                 (every? (partial available? ctx) (:opsv/artifact-refs record))
                 (evidence/valid-finalized-opsv-bundle? record bundle (:opsv/artifact-refs record)))
      (model/failure {} :invalid-finalized-evidence)
      (publication/call-with-exception-handling output #(publish-bundle! ctx output bundle)))))

(comment
  (assembly {}))
