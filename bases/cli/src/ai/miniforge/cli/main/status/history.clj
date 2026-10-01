;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.status.history
  "Discover readable workflow summaries in newest-first order."
  (:require [ai.miniforge.cli.app-config :as app-config]
            [ai.miniforge.cli.main.status.summary :as summary]
            [babashka.fs :as fs]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow-ids [directory]
  (if (fs/exists? directory)
    (->> (fs/list-dir directory) (filter fs/directory?) (map fs/file-name))
    []))

(defn- ^{:stratum 0} read-with-exception-handling [workflow-id]
  (try+
    (summary/read-workflow workflow-id)
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Error fatal (throw fatal))
    (catch Object _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} newest-first []
  (->> (app-config/events-dir)
       workflow-ids
       (keep read-with-exception-handling)
       (sort-by :last-updated #(compare %2 %1))))

(comment
  ::newest-first)
