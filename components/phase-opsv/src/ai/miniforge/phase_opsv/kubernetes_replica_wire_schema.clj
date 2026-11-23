;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-wire-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-json-patch :as patch]
            [ai.miniforge.phase-opsv.kubernetes-replica-change-schema :as replica]
            [ai.miniforge.phase-opsv.kubernetes-replica-payload :as wire]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} operation [op path value]
  [:map {:closed true} ["op" [:= op]] ["path" [:= path]] ["value" value]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Request
  [:map {:closed true}
   [:method [:= :patch]] [:path schema/NonBlankString] [:media-type [:= patch/media-type]]
   [:body [:tuple (operation "test" patch/uid-path schema/NonBlankString)
           (operation "test" patch/revision-path schema/NonBlankString)
           (operation "replace" wire/replicas-path replica/Count)]]])

(comment ::closed-single-replica-patch-no-caller-http-options)
