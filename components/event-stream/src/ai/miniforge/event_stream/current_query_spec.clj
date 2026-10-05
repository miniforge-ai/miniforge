;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-query-spec
  "Query options for supported current-profile history; references are not scope membership.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Options
  [:map {:closed true}
   [:scope {:optional true} [:tuple [:enum :chain :supervisory-entity :workflow] :uuid]]
   [:workflow-id {:optional true} :uuid]
   [:event-type {:optional true} qualified-keyword?]
   [:offset {:optional true} nat-int?]
   [:limit {:optional true} nat-int?]])

(comment
  Options)
