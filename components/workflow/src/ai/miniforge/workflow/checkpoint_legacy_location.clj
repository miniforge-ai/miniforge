;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-legacy-location
  "Recover legacy checkpoint identity only from its declared directory layout."
  (:require [ai.miniforge.event-stream.interface :as events]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private path-fields
  {:workflow/checkpoint-written :checkpoint/path
   :workflow/machine-snapshot-written :snapshot/path})

(defn- ^{:stratum 0} rooted-location [id root]
  (if (and (string? root) (seq root) (fs/absolute? root))
    (events/checkpoint-location root (str (fs/path root (str id))))
    :unknown))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} written? [event]
  (contains? path-fields (:event/type event)))

(defn- ^{:stratum 1} run-directory [event]
  (let [field (get path-fields (:event/type event))
        path (get event field)
        phase? (= :workflow/checkpoint-written (:event/type event))]
    (when (string? path)
      (some-> path fs/parent (cond-> phase? fs/parent)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} from-event [id event]
  (let [directory (when-not (contains? event :checkpoint/root) (run-directory event))]
    (cond
      (contains? event :checkpoint/root) (rooted-location id (:checkpoint/root event))
      (and directory (fs/absolute? directory) (= (str id) (str (fs/file-name directory))))
      (events/checkpoint-location (str (fs/parent directory)) (str directory))
      :else :unknown)))

(comment ::legacy-layout-compatibility)
