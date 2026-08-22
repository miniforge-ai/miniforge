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
(ns ai.miniforge.cli.main.display.classified-error
  "Classified-error display for CLI output: per-type headers, per-type
   context (including partial-work listings), the retry recommendation,
   and the composite `print-classified-error` view.

   Layer 0: Per-type header/context printers and the retry-recommendation
            message lookup
   Layer 1: Error-type dispatch (header-by-type, context-by-type) and the
            styled retry-recommendation printer
   Layer 2: `print-classified-error` — the composite view

   Extracted from `ai.miniforge.cli.main.display` (rule 210: the combined
   namespace measured 5 real layers, max 3). Generic ANSI styling
   (`style`) still lives there and is required here."
  (:require
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} print-agent-backend-error-context
  [completed-work]
  (if (seq completed-work)
    (println (messages/t :classified-error/agent-backend-context-success))
    (println (messages/t :classified-error/agent-backend-context))))

(defn ^{:stratum 0} print-task-code-error-context
  [completed-work]
  (println (messages/t :classified-error/task-code-context))
  (when (seq completed-work)
    (println)
    (println (messages/t :classified-error/partial-work))
    (doseq [work completed-work]
      (println (str "  ⏸️  " work)))))

(defn ^{:stratum 0} get-retry-recommendation
  [error-type]
  (case error-type
    :task-code (messages/t :classified-error/retry-task-code)
    :external (messages/t :classified-error/retry-external)
    :agent-backend (messages/t :classified-error/retry-agent-backend)
    (messages/t :classified-error/retry-generic)))

;; Error classification display
(defn ^{:stratum 0} print-agent-backend-error-header
  [completed-work]
  (println (display/style (messages/t :classified-error/agent-backend-header)
                          :foreground :yellow :bold true))
  (when (seq completed-work)
    (println)
    (println (display/style (messages/t :classified-error/task-completed)
                            :foreground :green))
    (doseq [work completed-work]
      (println (str "  " (display/style "✅" :foreground :green) " " work)))))

(defn ^{:stratum 0} print-task-code-error-header
  []
  (println (display/style (messages/t :classified-error/task-code-header)
                          :foreground :red :bold true)))

(defn ^{:stratum 0} print-external-error-header
  []
  (println (display/style (messages/t :classified-error/external-header)
                          :foreground :yellow :bold true)))

(defn ^{:stratum 0} print-generic-error-header
  []
  (println (display/style (messages/t :classified-error/generic-header)
                          :foreground :red :bold true)))

(defn ^{:stratum 0} print-external-error-context
  [completed-work]
  (println (messages/t :classified-error/external-context))
  (when (seq completed-work)
    (println)
    (println (messages/t :classified-error/partial-work))
    (doseq [work completed-work]
      (println (str "  " (display/style "✅" :foreground :green) " " work)))))

(defn ^{:stratum 0} print-error-report-url
  [report-url vendor]
  (when report-url
    (println)
    (println (str (display/style (messages/t :classified-error/report-prefix)
                                 :foreground :cyan)
                  vendor ":"))
    (println (str "   " report-url))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} print-error-header-by-type
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-header completed-work)
    :task-code (print-task-code-error-header)
    :external (print-external-error-header)
    (print-generic-error-header)))

(defn ^{:stratum 1} print-error-context
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-context completed-work)
    :task-code (print-task-code-error-context completed-work)
    :external (print-external-error-context completed-work)
    nil))

(defn ^{:stratum 1} print-retry-recommendation
  [should-retry error-type completed-work]
  (println)
  (if should-retry
    (println (str (display/style (messages/t :classified-error/recommendation-prefix)
                                 :foreground :cyan)
                 (get-retry-recommendation error-type)))
    (println (str (display/style (messages/t :classified-error/no-retry-prefix)
                                 :foreground :cyan)
                 (if (seq completed-work)
                   (messages/t :classified-error/no-retry-success)
                   (messages/t :classified-error/no-retry-failure))))))

;------------------------------------------------------------------------------ Layer 2

;; Composite error display
(defn ^{:stratum 2} print-classified-error
  "Display a classified error with rich formatting."
  [error-classification]
  (when error-classification
    (let [{:keys [type message completed-work report-url should-retry vendor]} error-classification]
      (print-error-header-by-type type completed-work)
      (println)
      (println (str "  " message))
      (println)
      (print-error-context type completed-work)
      (print-error-report-url report-url vendor)
      (print-retry-recommendation should-retry type completed-work))))
