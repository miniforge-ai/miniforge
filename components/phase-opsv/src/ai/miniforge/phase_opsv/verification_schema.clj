;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-schema
  (:require [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} correlation-keys
  "Invocation and content identity shared by preparation and measurement receipts."
  [:verification/id :candidate/hash :experiment-pack/hash])

(def ^{:stratum 0} Identity
  [:map {:closed true}
   [:verification/id :uuid]
   [:candidate/hash :string]
   [:experiment-pack/hash :string]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Receipt
  (mu/merge Identity
    [:map {:closed true}
     [:environment-fingerprint [:and [:map-of :keyword :any] [:fn seq]]]
     [:observations [:map-of :string :any]]
     [:confidence [:and number? [:fn #(<= 0 % 1)]]]
     [:metric-snapshot-artifact-refs [:vector {:min 1} :uuid]]]))

(comment ::shared-verification-identity-and-measurement-schema)
