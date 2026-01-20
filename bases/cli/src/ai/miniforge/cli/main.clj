;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.cli.main
  "Miniforge CLI entry point.

   Provides commands for interacting with miniforge workflows, agents,
   and the meta-loop.

   Usage:
     miniforge <command> [options]

   Commands:
     status       Show system status
     workflows    List workflows
     workflow     Show workflow detail
     meta         Show meta-loop status
     version      Show version information
     help         Show this help message"
  (:require
   [clojure.string :as str]))

;; ============================================================================
;; Version Information
;; ============================================================================

(defn version-info
  "Get version information from build metadata or default."
  []
  {:version "dev"
   :build   "development"
   :date    (str (java.time.LocalDate/now))})

;; ============================================================================
;; Command Handlers
;; ============================================================================

(defn show-version
  "Display version information."
  []
  (let [{:keys [version build date]} (version-info)]
    (println "miniforge" version)
    (println "Build:" build)
    (println "Date:" date)))

(defn show-help
  "Display help message."
  []
  (println "miniforge - Autonomous SDLC Platform")
  (println)
  (println "Usage:")
  (println "  miniforge <command> [options]")
  (println)
  (println "Commands:")
  (println "  status       Show system status")
  (println "  workflows    List all workflows")
  (println "  workflow     Show workflow detail")
  (println "  meta         Show meta-loop status")
  (println "  version      Show version information")
  (println "  help         Show this help message")
  (println)
  (println "For more information, visit: https://miniforge.ai"))

(defn show-status
  "Display system status."
  []
  (println "┌─────────────────────────────────────────────────────────────────┐")
  (println "│ MINIFORGE STATUS                                                │")
  (println "├─────────────────────────────────────────────────────────────────┤")
  (println "│ Status: Development                                             │")
  (println "│ Version:" (str/join (repeat (- 53 (count (:version (version-info)))) " ")) (:version (version-info)) "│")
  (println "└─────────────────────────────────────────────────────────────────┘")
  (println)
  (println "Note: Full status reporting will be available when the reporting")
  (println "      component is integrated."))

(defn show-workflows
  "Display workflow list."
  []
  (println "Workflows:")
  (println)
  (println "No workflows running (orchestrator not started)")
  (println)
  (println "Use 'miniforge help' for more information."))

(defn show-meta
  "Display meta-loop status."
  []
  (println "Meta-Loop Status:")
  (println)
  (println "Status: Not initialized")
  (println)
  (println "The meta-loop will be available when the operator component")
  (println "is integrated with the orchestrator."))

(defn unknown-command
  "Handle unknown commands."
  [cmd]
  (println "Unknown command:" cmd)
  (println)
  (show-help)
  (System/exit 1))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for miniforge CLI."
  [& args]
  (let [command (first args)]
    (case command
      "version"   (show-version)
      "help"      (show-help)
      nil         (show-help)
      "status"    (show-status)
      "workflows" (show-workflows)
      "workflow"  (show-workflows) ;; TODO: implement workflow detail
      "meta"      (show-meta)
      (unknown-command command)))
  (System/exit 0))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Test commands
  (-main "help")
  (-main "version")
  (-main "status")
  (-main "workflows")
  (-main "meta")
  (-main "unknown")

  :end)
