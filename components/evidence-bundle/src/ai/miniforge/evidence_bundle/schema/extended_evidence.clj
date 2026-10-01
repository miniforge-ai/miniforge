;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.extended-evidence
  "N6 annotation and pack-run sections, optional at the enclosing boundary."
  (:require [ai.miniforge.evidence-bundle.schema.pack-records :as pack]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Annotation
  [:map
   [:annotation/id uuid?]
   [:annotation/type [:enum :recommendation :warning :insight :question]]
   [:annotation/source [:map [:listener-id uuid?] [:principal string?]]]
   [:annotation/target [:map [:workflow-id uuid?] [:event-id uuid?]]]
   [:annotation/content [:map [:title string?] [:body string?] [:severity keyword?]]]
   [:annotation/timestamp inst?]])

(def ^{:stratum 0} PackRun
  [:map
   [:pack-run/id uuid?] [:pack/id string?] [:pack/version string?]
   [:pack/content-hash string?] [:pack/publisher string?] [:pack/entrypoint string?]
   [:pack/signature-verified? boolean?]
   [:pack/signature-error {:optional true} string?]
   [:pack/capabilities-required [:vector pack/Capability]]
   [:pack/capabilities-granted [:vector pack/GrantedCapability]]
   [:pack/capabilities-denied {:optional true} [:vector pack/DeniedCapability]]
   [:pack/resolved-dependencies {:optional true} [:vector pack/Dependency]]
   [:pack-run/inputs map?] [:pack-run/outputs map?]
   [:pack-run/connector-actions [:vector pack/ConnectorAction]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} annotations? (m/validator [:vector Annotation]))

(def ^{:stratum 1} pack-run? (m/validator PackRun))

(comment
  (pack-run? {}))
