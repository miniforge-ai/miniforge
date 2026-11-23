;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.protected-material
  "Bind protected material to its producer, and verify retained rather than regenerated findings."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.runtime-context :as context]
            [ai.miniforge.redaction.interface :as redaction])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private binding-fields
  [:workflow/id :opsv/evidence-bundle-id :opsv/material-kind :content/hash])

(def ^{:stratum 0} ^:private artifact-version "1.0.0")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record [ctx kind type content protection]
  (let [workflow-id (context/workflow-id ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        ;; Nil protection is used only to verify clean, pre-upgrade immutable records.
        bound (if (nil? protection) content
                  {:artifact/content content :artifact/metadata protection
                   :artifact/type type :artifact/version artifact-version})]
    (anomaly/let-ok [digest (artifact/content-digest [workflow-id bundle-id kind bound])]
      (artifact/build-artifact
        {:id (UUID/nameUUIDFromBytes (.getBytes ^String digest StandardCharsets/UTF_8))
         :type type :version artifact-version :content content
         :metadata (merge protection {:workflow/id workflow-id
                                      :opsv/evidence-bundle-id bundle-id
                                      :opsv/material-kind kind :content/hash digest})}))))

(defn- ^{:stratum 1} protection [value]
  (apply dissoc (:artifact/metadata value) binding-fields))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid? [ctx value]
  (let [metadata (protection value)
        content (:artifact/content value)
        legacy? (empty? metadata)
        protected {:artifact/content content :artifact/metadata metadata}]
    (and (if legacy? (and (redaction/supported? value) (redaction/clean? value))
             (evidence/valid-protected-artifact-content? protected))
         (= value (record ctx (get-in value [:artifact/metadata :opsv/material-kind])
                            (:artifact/type value) content (when-not legacy? metadata))))))
