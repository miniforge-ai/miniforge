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
(ns ai.miniforge.subprocess.config
  "Capture limits, read from config/subprocess/capture.edn."
  (:require
   [ai.miniforge.config.interface :as config]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-max-captured-bytes
  "Limit used when config/subprocess/capture.edn leaves the key out:
   64 MiB, the value that file ships with."
  (* 64 1024 1024))

(def ^{:stratum 0} capture-config-path
  "Classpath resource holding the capture limits."
  "config/subprocess/capture.edn")

(def ^{:stratum 0} CaptureConfig
  "A limit is a positive byte count a ByteArrayOutputStream can hold."
  [:map [:subprocess/max-captured-bytes [:int {:min 1 :max Integer/MAX_VALUE}]]])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} load-capture-config
  "The shipped config over the defaults. A missing or malformed file, or a
   value that fails `CaptureConfig`, throws, so a bad limit stops startup
   instead of reaching a capture."
  []
  (->> (config/load-config-resource capture-config-path)
       (merge {:subprocess/max-captured-bytes default-max-captured-bytes})
       (m/coerce CaptureConfig)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} max-captured-bytes
  (get (load-capture-config) :subprocess/max-captured-bytes))

;------------------------------------------------------------------------------ Rich Comment
(comment
  max-captured-bytes
  :leave-this-here)
