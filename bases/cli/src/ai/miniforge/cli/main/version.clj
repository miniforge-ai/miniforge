;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.version
  (:require [ai.miniforge.cli.app-config :as app-config]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} info
  {:name (app-config/binary-name)
   :version "2026.01.20.1"
   :description (app-config/description)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} print! []
  (println (str (:name info) " " (:version info)))
  (println (:description info)))

(comment
  (print!))
