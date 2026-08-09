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
(ns ai.miniforge.cli.workflow-runner.display-print
  "Printing of workflow header, summary, and final result to stdout."
  (:require
   [clojure.pprint]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.display-ansi :as ansi]
   [ai.miniforge.cli.workflow-runner.display-format :as fmt]
   [ai.miniforge.cli.workflow-runner.display-summary :as summary]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} print-workflow-header [workflow-id version quiet?]
  (when-not quiet?
    (println (ansi/colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
    (println (ansi/colorize :bold (messages/t :workflow-runner/header
                                              {:display-name (app-config/display-name)})))
    (println (ansi/colorize :cyan (messages/t :workflow-runner/workflow
                                              {:workflow-id (name workflow-id)})))
    (println (ansi/colorize :cyan (messages/t :workflow-runner/version
                                              {:version version})))
    (println (ansi/colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
    (flush)))

(defn ^{:stratum 0} print-workflow-summary [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)]
    (println (if success?
               (ansi/colorize :green (messages/t :workflow-runner/summary-success))
               (ansi/colorize :red (messages/t :workflow-runner/summary-failure))))
    (when metrics
      (println (messages/t :workflow-runner/metrics
                           {:tokens (:tokens metrics 0)
                            :cost (format "%.4f" (:cost-usd metrics 0.0))
                            :duration (fmt/format-duration (:duration-ms metrics 0))})))
    (when (seq errors)
      (println (ansi/colorize :red (str "\n" (messages/t :workflow-runner/errors))))
      (doseq [err errors]
        (println (str "  • " err))))))

(defn ^{:stratum 0} print-pretty-result
  "Print a compact human-readable summary of the workflow result.
   Full EDN dump is available via --output edn."
  [result]
  (println (summary/format-compact-summary result)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} print-result [result {:keys [output quiet]}]
  (when-not quiet
    (case output
      :json   (println (json/generate-string result {:pretty true}))
      :pretty (print-pretty-result result)
      :edn    (clojure.pprint/pprint result)
      (clojure.pprint/pprint result)))
  (flush))
