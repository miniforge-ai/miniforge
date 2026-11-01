;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-location-spec
  "N3 checkpoint locators keep physical storage separate from logical run identity.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Location
  [:map
   [:checkpoint/root [:string {:min 1}]]
   [:checkpoint/run-directory [:string {:min 1}]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Located
  [:map [:checkpoint/location {:optional true} Location]
   [:checkpoint/root {:optional true} :string]])

(comment Location)
