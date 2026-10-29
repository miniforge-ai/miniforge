;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns version-stamp
  "Stamps a release version into a built CLI jar, as the classpath
   resource `ai.miniforge.cli.main.version` reads. `bb build:cli` calls
   `stamp-from-env!` after the build; with MINIFORGE_VERSION unset or
   blank the jar stays unstamped and `mf version` reports \"dev\"."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} resource-name
  "Path of the version resource inside the jar. Must equal
   ai.miniforge.cli.main.version/resource-name."
  "miniforge/version.edn")

(def ^{:stratum 0} version-env
  "Env var carrying the version to stamp (release.yml sets it from the tag)."
  "MINIFORGE_VERSION")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} requested-version
  "The version to stamp, from `env` (a map of env var name to value), or
   nil when it is unset or blank."
  [env]
  (let [v (get env version-env)]
    (when-not (str/blank? v)
      (str/trim v))))

(defn ^{:stratum 1} stamp-jar!
  "Write {:version version} into `jar` at resource-name, replacing any
   earlier stamp; every other entry is left as it was. Returns `jar`."
  [jar version]
  (let [uri (java.net.URI. (str "jar:" (.toUri (fs/path (fs/absolutize jar)))))]
    (with-open [zfs (java.nio.file.FileSystems/newFileSystem uri {})]
      (let [entry (.getPath zfs resource-name (into-array String []))]
        (fs/create-dirs (fs/parent entry))
        (java.nio.file.Files/write entry
                                   (.getBytes (pr-str {:version version}) "UTF-8")
                                   (into-array java.nio.file.OpenOption []))))
    jar))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stamp-from-env!
  "Stamp `jar` with the version in the process environment, if any, and
   say which way it went."
  [jar]
  (if-let [version (requested-version (System/getenv))]
    (do (stamp-jar! jar version)
        (println "🏷️  Stamped" jar "with version" version))
    (println "🏷️ " jar "is unstamped (set" version-env "to stamp it); `mf version` reports \"dev\"")))
