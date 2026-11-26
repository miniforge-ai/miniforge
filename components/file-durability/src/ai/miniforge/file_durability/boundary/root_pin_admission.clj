;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.root-pin-admission
  "Capture identity before pinning; transfer the pin only with an owning result."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.directory-lease :as lease]
            [ai.miniforge.file-durability.boundary.directory-pin :as pin]
            [ai.miniforge.file-durability.boundary.owned-resource :as owned]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.root-identity :as identity])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} deadline [limits]
  (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS (:pin/timeout-ms limits))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} call! [^File root limits operation]
  (let [path (directory/existing! root)
        before (identity/read-identity path)]
    (if (and (= path (.toPath root)) before)
      (anomaly/let-ok [held (pin/open! path (:pin/entry-limit limits) (deadline limits))]
        (owned/call-with-release! held lease/release! (partial operation before)))
      (boundary/unqualified-store root :storage/directory-pin))))

(comment (deadline {:pin/timeout-ms 1000}))
