;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.extended-fixtures
  (:require [ai.miniforge.evidence-bundle.dag-fixtures :as dag]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} capability {:capability/id "read" :capability/scope :repo})

(def ^{:stratum 0} dependency {:pack/id "example" :pack/version "1.0.0" :pack/content-hash "digest"})

(defn ^{:stratum 0} annotation []
  {:annotation/id (random-uuid) :annotation/type :insight
   :annotation/source {:listener-id (random-uuid) :principal "operator"}
   :annotation/target {:workflow-id (random-uuid) :event-id (random-uuid)}
   :annotation/content {:title "Audit" :body "Observed result" :severity :info}
   :annotation/timestamp dag/at})

(defn ^{:stratum 0} connector-action []
  {:action/capability "read" :action/timestamp dag/at :action/result :success})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} granted []
  (assoc capability :capability/granted-by "policy" :capability/granted-at dag/at))

(defn ^{:stratum 1} denied []
  (assoc capability :capability/denied-reason "Not approved"))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pack-run []
  (assoc dependency
         :pack-run/id (random-uuid) :pack/publisher "example" :pack/entrypoint "default"
         :pack/signature-verified? true :pack/capabilities-required [capability]
         :pack/capabilities-granted [(granted)] :pack/capabilities-denied [(denied)]
         :pack/resolved-dependencies [dependency] :pack-run/inputs {} :pack-run/outputs {}
         :pack-run/connector-actions [(connector-action)]))

(comment
  (pack-run))
