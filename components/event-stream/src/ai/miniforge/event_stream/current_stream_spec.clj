;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-stream-spec
  "Explicit ownership options for durable current-profile streams."
  (:require [ai.miniforge.event-stream.snowflake-spec :as snowflake]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Options
  [:map {:closed true}
   [:journal-directory :string]
   [:sinks {:optional true} [:vector ifn?]]
   [:logger {:optional true} :any]
   [:snowflake-generator {:optional true} snowflake/Generator]])

(comment
  Options)
