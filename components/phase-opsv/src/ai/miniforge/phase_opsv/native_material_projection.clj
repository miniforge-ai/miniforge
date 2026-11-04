;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-material-projection
  "Classify explicitly marked native evidence at its producer, before shared scanning."
  (:require [ai.miniforge.phase-opsv.kubernetes-reporting-fields :as fields]
            [ai.miniforge.phase-opsv.kubernetes-reporting-model :as model]
            [ai.miniforge.phase-opsv.kubernetes-reporting-policy :as policy]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.walk :as walk]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} carrier-node? [value]
  (if (map? value) (fields/node-supported? value) (not (and (set? value) (sorted? value)))))

(defn- ^{:stratum 0} protected-frame? [marker frame]
  (fields/classified-protected? marker (get frame policy/native-verification-key)))

(defn- ^{:stratum 0} reporting-copy [value]
  (let [prepared (model/reporting-copy value)]
    (assoc (:kubernetes/reporting prepared)
           :kubernetes/reporting-profile (:kubernetes/reporting-profile prepared))))

(defn- ^{:stratum 0} native-frame? [value]
  (and (map? value) (contains? value policy/native-verification-key)))

(defn- ^{:stratum 0} metadata-free-node? [value]
  (nil? (meta value)))

(defn- ^{:stratum 0} candidate? [frame]
  (let [value (get frame policy/native-verification-key)]
    (and (nil? (meta frame)) (map? value) (fields/supported? value)
         (or (not (contains? value :kubernetes/reporting-profile))
             (= policy/profile (:kubernetes/reporting-profile value))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} native-frames [value]
  (filter native-frame? (tree-seq coll? seq value)))

(defn- ^{:stratum 1} metadata-free? [value]
  (every? metadata-free-node? (tree-seq coll? seq value)))

(defn- ^{:stratum 1} node [value]
  (if (native-frame? value) (update value policy/native-verification-key reporting-copy) value))

(defn- ^{:stratum 1} compatible? [value frames]
  (and (every? candidate? frames)
       (or (empty? frames) (every? carrier-node? (tree-seq coll? seq value)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} supported? [value]
  ;; Metadata is outside the shared scanner's bounded data tree. Reject it without traversing it.
  (and (redaction/supported? value) (metadata-free? value) (compatible? value (native-frames value))))

(defn ^{:stratum 2} project [value]
  (if (seq (native-frames value)) (walk/postwalk node value) value))

(defn ^{:stratum 2} protected? [marker value]
  (every? (partial protected-frame? marker) (native-frames value)))

(comment ::classification-preserves-original-values-for-the-shared-scanner)
