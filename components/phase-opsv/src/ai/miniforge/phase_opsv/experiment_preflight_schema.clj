;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.experiment-preflight-schema
  (:require [ai.miniforge.kubernetes-target.interface :as target]
            [ai.miniforge.phase-opsv.kubernetes-preparation-schema :as preparation]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Context [:map [:execution/id :uuid]])

(def ^{:stratum 0} Disabled [:map {:closed true} [:experiment/enabled? [:= false]]])

(def ^{:stratum 0} Enabled
  [:map {:closed true}
   [:experiment/enabled? [:= true]]
   [:environment/class [:= :staging]]
   [:experiment/environment target/IdentityText]
   [:experiment/declaration preparation/Declaration]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Settings [:multi {:dispatch :experiment/enabled?} [false Disabled] [true Enabled]])

(def ^{:stratum 1} Arguments [:tuple Context preparation/Request])

(comment ::explicit-runtime-opt-in-and-independent-target-declaration)
