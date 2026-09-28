;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.artifact.publication-boundary
  "Convert filesystem and codec failures at the artifact publication boundary."
  (:require [ai.miniforge.artifact.publication :as publication]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} call-with-exception-handling [id operation]
  (try (operation)
       (catch InterruptedException _
         (let [failure (publication/failure :unavailable :publication/unconfirmed id)]
           (.interrupt (Thread/currentThread))
           failure))
       (catch Throwable _
         (publication/failure :unavailable :publication/unconfirmed id))))
