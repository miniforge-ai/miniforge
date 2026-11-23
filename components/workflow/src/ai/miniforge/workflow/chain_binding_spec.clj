;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-binding-spec
  "Definition expressions and explicit requirements, not runtime producer identities.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Path [:vector [:or :keyword :string :int]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Expression [:or :string :keyword Path])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Binding
  [:or Expression
   [:map {:closed true} [:binding/expression Path] [:binding/required? :boolean]]])

(comment
  Binding)
