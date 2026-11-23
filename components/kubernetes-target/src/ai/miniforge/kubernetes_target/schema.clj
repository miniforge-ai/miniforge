;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.schema
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} IdentityText
  [:and :string [:fn (complement str/blank?)]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Target
  [:map {:closed true}
   [:cluster IdentityText]
   [:namespace IdentityText]
   [:namespace-uid IdentityText]
   [:workload-uid IdentityText]])

(comment ::identity-declaration-not-observation-or-authority)
