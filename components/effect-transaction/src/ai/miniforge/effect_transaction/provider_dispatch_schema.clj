;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.provider-dispatch-schema
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Text [:and :string [:fn #(not (str/blank? %))]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Messages
  [:map {:closed true}
   [:dispatch/unconfirmed-message Text]
   [:dispatch/invalid-message Text]])

(def ^{:stratum 1} DiagnosticArguments [:tuple Text Text])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Arguments [:tuple fn? fn? Messages])

(comment ::runtime-functions-and-provider-owned-localized-diagnostics)
