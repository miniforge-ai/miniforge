;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governance-schema
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} RuntimePolicy
  [:map {:closed true}
   [:policy/context [:map-of :keyword any?]]
   [:policy/revision [:and :string [:fn #(not (str/blank? %))]]]
   [:policy/event-watermark [:int {:min 0}]]])

(comment ::runtime-policy-not-agent-input)
