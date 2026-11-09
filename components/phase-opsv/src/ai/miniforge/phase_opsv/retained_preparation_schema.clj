;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-schema
  (:require [ai.miniforge.phase-opsv.environment-comparison-schema :as comparison]
            [ai.miniforge.phase-opsv.environment-target-schema :as target]
            [ai.miniforge.phase-opsv.verification-environment-schema :as environment]
            [ai.miniforge.phase-opsv.verification-schema :as verification]
            [clojure.string :as str]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Context
  [:map
   [:execution/id :uuid]
   [:execution/input [:map [:opsv/evidence-bundle-id :uuid]]]
   [:execution/opts [:map [:opsv/artifact-directory [:and :string [:fn (complement str/blank?)]]]]]
   [:opsv/evidence-assembly-store [:fn #(instance? clojure.lang.IAtom %)]]])

(def ^{:stratum 0} Assembly
  [:map
   [:evidence-bundle/id :uuid]
   [:evidence-bundle/workflow-id :uuid]
   [:opsv.assembly/status [:enum :assembling :finalized]]
   [:opsv/artifact-refs [:set :uuid]]])

(def ^{:stratum 0} Request
  (mu/merge verification/Identity
    [:map {:closed false}
     [:comparison/pin comparison/Pin]
     [:runtime/target target/Target]]))

(def ^{:stratum 0} Content
  [:map {:closed true}
   [:comparison/pin comparison/Pin]
   [:comparison/preparation environment/PreparationReceipt]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Arguments [:tuple Context Request :uuid])

(comment ::acknowledgement-and-producer-binding-are-not-execution-authority)
