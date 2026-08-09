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
(ns ai.miniforge.cli.workflow-runner.display-summary
  "Assembly of the compact multi-line workflow summary."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-summary-lines :as lines]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} compact-summary-lines
  [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)
        hr       (apply str (repeat 65 "━"))]
    (concat [(ansi/colorize :cyan (str "\n" hr))
             (lines/status-line success?)]
            (lines/phase-lines result)
            (lines/failed-task-lines result)
            (lines/pr-lines result)
            (lines/metrics-lines metrics)
            (lines/error-lines success? errors)
            (lines/footer-lines hr))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} format-compact-summary
  "Build a compact multi-line string summarising a workflow result.

  Lines included:
  - Status (success/failure, colorized)
  - Phase table (when phase data present)
  - Failed task IDs (when present)
  - PR URLs (when present)
  - Metrics line (when metrics present)
  - Error list (on failure only)
  - Events directory pointer
  - '--output edn' hint

  Localizable text goes through messages/t; structural characters
  (separators, newlines, bullets) are emitted directly."
  ([result] (format-compact-summary result nil))
  ([result _workflow-id]
   (str/join "\n" (compact-summary-lines result))))
