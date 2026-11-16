;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.file-durability.boundary.attached-directory
  "Prove a canonical protected child remains on its parent's device."
  (:require [ai.miniforge.file-durability.boundary :as boundary]
            [ai.miniforge.file-durability.boundary.protected-directory :as directory]
            [ai.miniforge.file-durability.boundary.root-identity :as identity])
  (:import [java.io File]
           [java.nio.file Path]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} check! [^File parent ^File child]
  (let [anchor (directory/existing! parent)
        ^Path attached (directory/existing! child)]
    (if (and (= anchor (.toPath parent) (.getParent attached))
             (= attached (.toPath child))
             (identity/same-device? anchor attached))
      attached
      (boundary/unqualified-store child :storage/attached-directory))))
