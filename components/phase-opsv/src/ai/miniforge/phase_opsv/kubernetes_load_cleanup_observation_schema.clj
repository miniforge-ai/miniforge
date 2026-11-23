;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-cleanup-observation-schema
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Status
  [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Status"]] ["status" [:= "Success"]]
   ["code" {:optional true} [:enum 200 202]]
   ["metadata" {:optional true} [:map ["resourceVersion" {:optional true} schema/NonBlankString]]]
   ["details" [:map ["name" schema/NonBlankString] ["uid" schema/NonBlankString]
               ["kind" [:= "pods"]] ["group" {:optional true} [:= ""]]]]])

(comment ::status-details-kind-is-the-plural-resource-name-in-the-native-delete-producer)
