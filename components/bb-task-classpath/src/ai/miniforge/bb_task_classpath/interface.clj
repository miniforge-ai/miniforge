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
(ns ai.miniforge.bb-task-classpath.interface
  "Public API for the bb.edn task-classpath load check."
  (:require [ai.miniforge.bb-task-classpath.core :as core]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} check-specs
  [bb-edn]
  (core/check-specs bb-edn))

(defn ^{:stratum 0} load-program
  [spec]
  (core/load-program spec))

(defn ^{:stratum 0} check-all!
  [opts]
  (core/check-all! opts))
