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

(def ^{:stratum 0} Root
  [:map [:checkpoint/root {:optional true} :string]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Unlocated
  [:and Root [:map [:checkpoint/location {:optional true} [:not :any]]]])

(def ^{:stratum 1} Located
  [:and Root [:map [:checkpoint/location {:optional true} Location]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Profile
  [:multi {:dispatch :event/version}
   ["1.0.0" Unlocated]
   ["1.1.0" Located]])

(comment Location)
