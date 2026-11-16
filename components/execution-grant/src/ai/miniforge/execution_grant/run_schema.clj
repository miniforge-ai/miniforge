;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.run-schema
  "Internal durable run authority, not workflow status or an execution grant."
  (:require [ai.miniforge.execution-grant.store-schema :as store-schema]
            [clojure.string :as str]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} StopRequest
  [:map {:closed true}
   [:workflow-run/id :uuid]
   [:intervention/id :uuid]
   [:authority/stopped-at inst?]
   [:authority/reason [:and :string [:fn #(not (str/blank? %))]]]])

(def ^{:stratum 0} LookupArguments [:tuple store-schema/Directory :uuid])

(def ^{:stratum 0} RegisterArguments [:tuple store-schema/Directory :uuid inst?])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Record
  [:multi {:dispatch :authority/state}
   [:active [:map {:closed true}
             [:workflow-run/id :uuid]
             [:authority/state [:= :active]]
             [:authority/created-at inst?]]]
   [:full-stop (mu/merge StopRequest
                        [:map [:authority/state [:= :full-stop]]])]])

(def ^{:stratum 1} StopArguments [:tuple store-schema/Directory StopRequest])
