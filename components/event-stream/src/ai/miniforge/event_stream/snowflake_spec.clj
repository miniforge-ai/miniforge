;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.snowflake-spec
  "Structural generator contract shared by creation and runtime admission boundaries."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.snowflake-state-spec :as state]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Generator
  [:maybe [:or [:fn anomaly/any-anomaly?]
           [:map [:now-fn fn?] [:worker-id state/Worker] [:state [:fn state/valid?]]]]])

(comment
  Generator)
