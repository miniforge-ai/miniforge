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

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PrProposalInput
  [:map {:closed true}
   [:workflow-run/id :uuid]
   [:effect/id :uuid]
   [:pr/repo NonBlankString]
   [:pr/base NonBlankString]
   [:pr/branch NonBlankString]
   [:pr/head-sha NonBlankString]
   [:pr/title NonBlankString]
   [:opsv/policy-diff NonBlankString]
   [:opsv/evidence-bundle-id :uuid]
   [:opsv/rollback-instructions NonBlankString]
   [:opsv/verification-result opsv/VerificationResult]])
