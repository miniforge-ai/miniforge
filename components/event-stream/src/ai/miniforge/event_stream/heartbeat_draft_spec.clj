;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-draft-spec
  "Current heartbeat admission, not proof of producer coverage or lifecycle ownership."
  (:require [ai.miniforge.event-stream.heartbeat-fields-spec :as fields]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Draft
  [:and
   [:map
    [:event/type [:= :workflow/phase-heartbeat]]
    [:event/version [:= fields/payload-version]]
    [:scope/type [:= :workflow]]
    [:workflow/id :uuid]
    [:workflow/phase :keyword]]
   fields/Liveness fields/Progress [:fn fields/consistent-progress?]])

(comment ::heartbeat-contract)
