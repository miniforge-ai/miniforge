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
(ns ai.miniforge.agent-worker.native.settings
  "Per-kind native worker settings and the command timeout, read from
   config/agent-worker/native.edn."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} config-resource "config/agent-worker/native.edn")

(def ^{:stratum 0} default-command-timeout-ms
  "Fallback when the config is silent: every native command returns at once
   (the harness itself runs in the background)."
  30000)

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} native-config
  "The native settings, read once. A missing resource leaves no settings, so
   `create` answers worker-unavailable rather than throwing."
  (delay (some-> (io/resource config-resource) slurp edn/read-string)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} timeout-ms []
  (get @native-config :agent-worker/command-timeout-ms default-command-timeout-ms))

(defn ^{:stratum 2} for-kind [kind] (get-in @native-config [:agent-worker/native kind]))
