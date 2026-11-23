;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.execution-grant.opsv-profile
  (:require [ai.miniforge.execution-grant.opsv-scope-schema :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} operation-class? [class scope]
  (= class (get schema/operation-classes (get-in scope [:opsv/experiment :opsv/operation]))))

(defn- ^{:stratum 0} resource-identities? [scope]
  (or (= :start-load (get-in scope [:opsv/experiment :opsv/operation]))
      (every? :uid (get-in scope [:opsv/experiment :opsv/resources]))))

(defn- ^{:stratum 0} load-start-bounded? [scope]
  (or (not= :start-load (get-in scope [:opsv/experiment :opsv/operation]))
      (and (seq (get-in scope [:opsv/experiment :opsv/traffic-destinations]))
           (every? pos? (vals (get-in scope [:opsv/experiment :opsv/load-limits]))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} admissible? [class scope]
  (boolean (and (operation-class? class scope)
                (resource-identities? scope)
                (load-start-bounded? scope))))

(comment ::typed-binding-policy-shared-by-issuance-and-authorization)
