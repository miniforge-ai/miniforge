;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-retention-record
  "Immutable definition and invocation manifests sharing one canonical format."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.workflow.messages :as messages])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private format-version
  "Version of the retained chain manifest format."
  "1.0.0")

(defn ^{:stratum 0} failure [type key]
  (anomaly/anomaly type (messages/t key) {}))

(defn- ^{:stratum 0} definition-key [record]
  (anomaly/let-ok [digest (artifact/content-digest
                          [:miniforge.chain/definition-v1
                           (:chain/definition-id record) (:chain/definition-version record)])]
    (UUID/nameUUIDFromBytes (.getBytes ^String digest StandardCharsets/UTF_8))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} manifest [id kind content parents]
  (artifact/build-artifact {:id id :type :manifest :version format-version
                            :content {:chain/record-kind kind :chain/record content}
                            :parents parents}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} artifacts [record]
  (anomaly/let-ok [definition-id (definition-key record)]
    [(manifest definition-id :definition (:chain/definition-snapshot record) [])
     (manifest (:chain/run-id record) :invocation record [definition-id])]))

(comment
  ::immutable-chain-manifests)
