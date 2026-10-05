;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.boundary.current-query
  "Select committed current records by authoritative scope, then paginate."
  (:require [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.current-query-spec :as spec]
            [ai.miniforge.event-stream.scope-policy :as policy]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} selected? [{:keys [scope workflow-id event-type]} event]
  (let [authoritative (policy/scope event)]
    (and (or (nil? scope) (= scope authoritative))
         (or (nil? workflow-id) (= [:workflow workflow-id] authoritative))
         (or (nil? event-type) (= event-type (:event/type event))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} events [committed opts]
  (let [options (if (nil? opts) {} opts)]
    (if (m/validate spec/Options options)
      (->> committed
           (filter (partial selected? options))
           (drop (get options :offset 0))
           (take (get options :limit Long/MAX_VALUE))
           vec)
      (model/failure :invalid-input :publication/invalid-query nil))))

(comment
  (events [] {}))
