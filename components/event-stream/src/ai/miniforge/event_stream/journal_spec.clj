;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.journal-spec)

;------------------------------------------------------------------------------ Layer 0

;; This is the storage boundary's structural contract. Event-family validation
;; and authoritative scope selection remain the publisher's responsibility.
(def ^{:stratum 0} RecordContent
  [:map {:closed true}
   [:journal/scope [:tuple keyword? some?]]
   [:journal/event
    [:map
     [:event/id uuid?]
     [:event/type keyword?]
     [:event/timestamp inst?]
     [:event/version string?]
     [:event/sequence-number nat-int?]]]])

(comment
  RecordContent)
