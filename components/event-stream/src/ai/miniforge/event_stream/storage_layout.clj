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

(ns ai.miniforge.event-stream.storage-layout
  "Configuration-as-data for the event-stream on-disk storage layout."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private resource-path
  "config/event-stream/storage-layout.edn")

(def ^:private required-keys
  [:live-subdir
   :archived-subdir
   :operator-subdir
   :snapshot-filename
   :snapshot-tmp-filename
   :archived-marker-filename])

(defn- load-layout
  []
  (let [resource (or (io/resource resource-path)
                     (throw (ex-info "Missing event-stream storage layout resource"
                                     {:resource resource-path})))
        layout (edn/read-string (slurp resource))
        missing (seq (remove #(string? (get layout %)) required-keys))]
    (when missing
      (throw (ex-info "Invalid event-stream storage layout resource"
                      {:resource resource-path
                       :missing-string-keys (vec missing)})))
    layout))

(def ^:private layout
  (delay (load-layout)))

(defn live-subdir
  []
  (:live-subdir @layout))

(defn archived-subdir
  []
  (:archived-subdir @layout))

(defn operator-subdir
  []
  (:operator-subdir @layout))

(defn snapshot-filename
  []
  (:snapshot-filename @layout))

(defn snapshot-tmp-filename
  []
  (:snapshot-tmp-filename @layout))

(defn archived-marker-filename
  []
  (:archived-marker-filename @layout))
