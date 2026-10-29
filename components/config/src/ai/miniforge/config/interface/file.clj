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
(ns ai.miniforge.config.interface.file
  "Config-file writers that share one lock. Apart from config.interface so bb
   tasks loading that on a minimal classpath need no anomaly or file-durability."
  (:require [ai.miniforge.config.file :as file]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} update-config!
  "Write `(f current)` to `path` under the config lock; `f` gets nil for an
   absent or blank file. {:before :after}, or an anomaly naming the file, with
   nothing written, when it is not one EDN map, the lock is busy or the write fails."
  [path f]
  (file/update-config! path f))

(defn ^{:stratum 0} write-config!
  "Write `config` to `path` under the config lock, whatever the file held.
   `config` or an anomaly."
  [path config]
  (file/write-config! path config))
