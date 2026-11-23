;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.version
  "The CLI's version. Release builds stamp it into the jar as a classpath
   resource (`MINIFORGE_VERSION=<v> bb build:cli`, see
   tasks/version_stamp.clj); a build without that resource reports the
   dev fallback."
  (:require [ai.miniforge.cli.app-config :as app-config]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} resource-name
  "Classpath path of the stamped version resource. Must equal
   version-stamp/resource-name (tasks/version_stamp.clj)."
  "miniforge/version.edn")

(def ^{:stratum 0} fallback-version
  "Reported by builds that carry no stamped version (source checkouts,
   unstamped `bb build:cli`)."
  "dev")

(defn ^{:stratum 0} resource-version
  "The non-blank :version string in the EDN map at `resource` (a URL or
   anything `slurp` accepts), or nil when `resource` is nil or carries none."
  [resource]
  (when resource
    (let [v (:version (edn/read-string (slurp resource)))]
      (when (and (string? v) (not (str/blank? v)))
        v))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} info []
  {:name (app-config/binary-name)
   :version (or (resource-version (io/resource resource-name))
                fallback-version)
   :description (app-config/description)})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} print! []
  (let [{:keys [name version description]} (info)]
    (println (str name " " version))
    (println description)))

(comment
  (info)
  (print!))
