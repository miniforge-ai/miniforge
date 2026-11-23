;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-runtime-spec)

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PositiveLong [:and int? [:> 0]])

(def ^{:stratum 0} Source [:and qualified-keyword? [:not [:= :workflow/phase-heartbeat]]])

(def ^{:stratum 0} StopOptions [:map {:closed true} [:may-interrupt? {:optional true} :boolean]])

(def ^{:stratum 0} Handle [:map [:heartbeat/current? [:= true]]
             [:heartbeat/state [:fn #(instance? clojure.lang.Atom %)]]])

(def ^{:stratum 0} State [:map [:closed? :boolean] [:dropped [:and int? [:>= 0]]] [:progress :map]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Options
  [:map {:closed true}
   [:interval-ms {:optional true} PositiveLong]
   [:progress-sources {:optional true} [:or [:= :unknown] [:set Source]]]])

(def ^{:stratum 1} Observation [:tuple Source PositiveLong])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} StartArguments [:tuple :uuid :keyword Options])

(comment ::current-heartbeat-runtime-contract)
