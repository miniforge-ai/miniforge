;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.measurement-schema
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RangeSeries
  [:map
   [:metric [:map-of :string :string]]
   [:values [:vector {:min 1} [:tuple number? :string]]]
   [:histograms {:optional true} [:vector {:max 0} :any]]])

(def ^{:stratum 0} EmptyAnnotations [:maybe [:vector {:max 0} :string]])

(def ^{:stratum 0} WindowRequest
  [:map {:closed true}
   [:prometheus/query schema/NonBlankString]
   [:window/start-seconds nat-int?]
   [:window/end-seconds pos-int?]
   [:window/step-seconds pos-int?]
   [:measurement/max-samples [:int {:min 2}]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} RangeResponse
  [:map
   [:status [:= "success"]]
   [:error {:optional true} [:not :any]]
   [:errorType {:optional true} [:not :any]]
   [:warnings {:optional true} EmptyAnnotations]
   [:infos {:optional true} EmptyAnnotations]
   [:data [:map
           [:resultType [:= "matrix"]]
           [:result [:vector {:min 1 :max 1} RangeSeries]]]]])

(comment ::prometheus-range-wire-contract)
