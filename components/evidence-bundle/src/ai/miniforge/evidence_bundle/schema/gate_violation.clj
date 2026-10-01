;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-violation
  "Recorded gate violations use the authoritative N4 section 3.3 shape."
  (:require [ai.miniforge.evidence-bundle.schema.outcome-reliability :as reliability]
            [ai.miniforge.schema.interface :as shared]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} location
  [:map [:file {:optional true} string?] [:line {:optional true} nat-int?]
   [:column {:optional true} nat-int?] [:resource-type {:optional true} string?]
   [:resource-name {:optional true} string?]])

(def ^{:stratum 0} remediation
  [:multi {:dispatch :type}
   [:diff [:map [:type [:= :diff]] [:file string?] [:patch string?]]]
   [:replacement [:map [:type [:= :replacement]] [:file string?] [:line nat-int?]
                  [:old-value :any] [:new-value :any]]]])

(defn ^{:stratum 0} execution-failure-consistent? [record]
  (or (not (contains? record :failure/class)) (false? (:violation/auto-fixable? record))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} record-fields
  [:map
   [:violation/id uuid?] [:violation/rule-id keyword?]
   [:violation/pack-id keyword?] [:violation/gate-id keyword?]
   [:violation/severity (into [:enum] shared/severities)]
   [:violation/message string?] [:violation/auto-fixable? boolean?]
   [:violation/remediation string?]
   [:violation/location {:optional true} location]
   [:violation/remediation-code {:optional true} remediation]
   [:violation/context {:optional true} map?]
   [:violation/documentation-url {:optional true} string?]
   [:failure/class {:optional true} [:fn reliability/failure-class?]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} record-schema [:and record-fields [:fn execution-failure-consistent?]])
