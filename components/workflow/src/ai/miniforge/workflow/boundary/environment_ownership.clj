;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.environment-ownership
  "Release an acquired environment unless its ownership record is transferred."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [ai.miniforge.workflow.boundary.environment :as environment]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} retain! [record operation]
  (vreset! record (operation)))

(defn- ^{:stratum 0} release-unretained! [record executor environment-id]
  (when-not @record (environment/release! executor environment-id)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} transfer! [executor environment-id operation]
  (let [record (volatile! nil)]
    (cleanup/finish! (partial retain! record operation)
                     (partial release-unretained! record executor environment-id))
    @record))

(comment ::acquisition-ownership-transfer)
