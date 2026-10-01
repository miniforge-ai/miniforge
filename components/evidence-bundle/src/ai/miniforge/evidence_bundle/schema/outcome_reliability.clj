;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.outcome-reliability
  "N6 outcome reliability values; domain enumerations belong to their owners."
  (:require [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.reliability.interface :as reliability]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} finite-double? [value]
  (and (double? value) (Double/isFinite value)))

(defn ^{:stratum 0} consistent? [outcome]
  (and (map? outcome)
       (or (not (true? (:outcome/success outcome)))
           (nil? (:outcome/failure-class outcome)))))

(def ^{:stratum 0} failure-class? failure/valid-failure-class?)

(defn ^{:stratum 0} tier? [value]
  (schema/valid? reliability/WorkflowTier value))

(defn ^{:stratum 0} degradation-mode? [value]
  (schema/valid? reliability/DegradationMode value))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} SliMeasurements
  [:vector
   [:map
    [:sli/name reliability/SliName]
    [:sli/value [:fn finite-double?]]
    [:sli/target {:optional true} [:fn finite-double?]]
    [:sli/met? {:optional true} :boolean]]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} sli-measurements? [value]
  (schema/valid? SliMeasurements value))

(comment
  (sli-measurements? [{:sli/name :SLI-1 :sli/value 0.99}]))
