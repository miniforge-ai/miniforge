;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-selection
  "Selection requests and resolved-definition results, independent of resource I/O."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} latest? [version]
  (contains? #{nil :latest "latest"} version))

(defn ^{:stratum 0} request [id version]
  {:chain-id id :version version})

(defn- ^{:stratum 0} resolved-version? [version]
  (and (schema/valid? schema/NonBlankString version) (not= "latest" version)))

(defn- ^{:stratum 0} failure [type key data]
  (anomaly/anomaly type (messages/t key data) data))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid-request? [{:keys [chain-id version]}]
  (and (keyword? chain-id) (or (latest? version) (resolved-version? version))))

(defn ^{:stratum 1} invalid-request [request]
  (failure :invalid-input :chain/invalid-request request))

(defn ^{:stratum 1} read-failure [path error]
  (failure :fault :chain/read-failed {:resource-path path :error error}))

(defn- ^{:stratum 1} matches? [{:keys [chain-id version]} definition]
  (and (map? definition)
       (= chain-id (:chain/id definition))
       (resolved-version? (:chain/version definition))
       (or (latest? version) (= version (:chain/version definition)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} result [request path definition]
  (cond
    (nil? path) (failure :not-found :chain/not-found request)
    (anomaly/anomaly? definition) definition
    (matches? request definition) {:chain definition :source :resource :path path}
    :else (failure :invalid-input :chain/definition-mismatch
                   (assoc request :actual-id (:chain/id definition)
                                  :actual-version (:chain/version definition)))))

(comment
  (request :example "1.0.0"))
