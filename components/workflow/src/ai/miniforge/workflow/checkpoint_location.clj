;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-location
  "Decode checkpoint announcement identity at the retained-event boundary."
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.checkpoint-legacy-location :as legacy]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private supported-version-prefix
  "Known major schema; higher minor versions preserve these field meanings."
  "1.")

(def ^{:stratum 0} ^:private unlocated-version-prefix
  "Payload 1.0 predates paired storage identity; that key cannot establish a locator."
  "1.0.")

(defn- ^{:stratum 0} explicit-location [event]
  (let [location (:checkpoint/location event)]
    (if (and (schema/valid? events/CheckpointLocation location)
             (fs/absolute? (:checkpoint/root location))
             (fs/absolute? (:checkpoint/run-directory location)))
      location
      :unknown)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} compatible? [event]
  (let [version (:event/version event)]
    (and (schema/valid? schema/SemanticVersion version)
         (str/starts-with? version supported-version-prefix))))

(defn- ^{:stratum 1} misversioned-location? [event]
  (and (contains? event :checkpoint/location)
       (str/starts-with? (:event/version event) unlocated-version-prefix)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} from-event [id event]
  (cond
    (not (and (map? event) (qualified-keyword? (:event/type event)))) :unknown
    (not (legacy/written? event)) nil
    (not= (str id) (str (:workflow/id event))) :unknown
    (not (compatible? event)) :unknown
    (misversioned-location? event) :unknown
    (contains? event :checkpoint/location) (explicit-location event)
    :else (legacy/from-event id event)))

(comment ::retained-checkpoint-identity)
