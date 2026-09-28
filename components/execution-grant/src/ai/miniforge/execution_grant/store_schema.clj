;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.store-schema
  "Contracts for durable runtime grant authority."
  (:require [ai.miniforge.execution-grant.schema :as schema]
            [clojure.string :as str])
  (:import [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Directory
  [:and [:or :string [:fn #(instance? File %)]]
   [:fn #(not (str/blank? (str %)))]])

(def ^{:stratum 0} Revocation
  [:map {:closed true}
   [:grant/id :uuid]
   [:grant/revoked-at inst?]
   [:grant/revocation-reason (into [:enum] schema/revocation-reasons)]])

(def ^{:stratum 0} IssuedGrant
  [:and schema/ExecutionGrant
   [:fn #(and (nil? (:grant/revoked-at %)) (nil? (:grant/revocation-reason %)))]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} RegisterArguments [:tuple Directory IssuedGrant])

(def ^{:stratum 1} LookupArguments [:tuple Directory :uuid])

(def ^{:stratum 1} RevokeArguments
  [:tuple Directory :uuid (into [:enum] schema/revocation-reasons) inst?])
