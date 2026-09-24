;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.schema
  "Closed inputs for evidence-bearing provider proposals."
  (:require [ai.miniforge.opsv.interface :as opsv]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlankString
  [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} GitObjectId
  [:re #"(?:[0-9a-f]{40}|[0-9a-f]{64})"])

(def ^{:stratum 0} EvidenceValue
  "Portable evidence: scalar EDN, vectors and string/keyword-keyed maps.
   Runtime objects and arbitrary tags cannot enter a durable provider payload."
  [:schema
   {:registry
    {::value [:or :nil :boolean :string :keyword :uuid
              [:fn rational?] [:fn float?]
              [:vector [:ref ::value]]
              [:map-of [:or :string :keyword] [:ref ::value]]]}}
   [:ref ::value]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PrProposalInput
  [:map {:closed true}
   [:workflow-run/id :uuid]
   [:effect/id :uuid]
   [:pr/repo NonBlankString]
   [:pr/base NonBlankString]
   [:pr/branch NonBlankString]
   [:pr/head-sha GitObjectId]
   [:pr/title NonBlankString]
   [:opsv/policy-diff NonBlankString]
   [:opsv/evidence-bundle-id :uuid]
   [:opsv/rollback-instructions NonBlankString]
   [:opsv/verification-result [:and opsv/VerificationResult EvidenceValue]]])
