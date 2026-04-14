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

(ns ai.miniforge.cli.main.display
  "Terminal styling and error display for CLI output."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; ANSI styling primitives

(def ansi-colors
  {:red     "31"
   :green   "32"
   :yellow  "33"
   :blue    "34"
   :magenta "35"
   :cyan    "36"
   :white   "37"})

(defn style
  "Apply terminal styling using ANSI escape codes."
  [text & {:keys [foreground bold]}]
  (let [codes (cond-> []
                bold (conj "1")
                foreground (conj (get ansi-colors foreground "37")))]
    (if (seq codes)
      (str "\033[" (str/join ";" codes) "m" text "\033[0m")
      text)))

(defn print-error [msg]
  (println (style (messages/t :classified-error/error-prefix
                              {:message msg})
                  :foreground :red)))

(defn print-success [msg]
  (println (style msg :foreground :green)))

(defn print-info [msg]
  (println (style msg :foreground :cyan)))

;------------------------------------------------------------------------------ Layer 1
;; Data-driven detail rendering

(defn render-fields
  "Render entity fields from a data-driven spec.
   Each field is [data-key message-key & [opts-map]].
   Skips nil values. Supports :default, :transform, and :param (default :value)."
  [entity fields]
  (doseq [[data-key msg-key & [{:keys [default transform param]}]] fields]
    (let [raw-val (get entity data-key)
          val     (if (nil? raw-val) default raw-val)]
      (when-not (nil? val)
        (let [display-val (if transform (transform val) (str val))
              param-key   (or param :value)]
          (println (messages/t msg-key {param-key display-val})))))))

(defn render-section
  "Render a titled section with child entries.
   section: {:key K :header H :entry E :entry-fn (fn [item] -> params) :max N}"
  [entity {:keys [key header entry entry-fn max]}]
  (when-let [items (seq (get entity key))]
    (when header
      (println (messages/t header {:count (count items)})))
    (when entry
      (doseq [item (cond->> items max (take max))]
        (println (messages/t entry (if entry-fn (entry-fn item) {:value (str item)})))))))

(defn render-detail
  "Render a complete detail view: header + fields + sections."
  [{:keys [header header-params fields sections]} entity]
  (println)
  (when header
    (println (style (messages/t header header-params) :foreground :cyan :bold true)))
  (render-fields entity fields)
  (doseq [section sections]
    (render-section entity section))
  (println))

;; Error classification display

(defn print-agent-backend-error-header
  [completed-work]
  (println (style (messages/t :classified-error/agent-backend-header)
                  :foreground :yellow :bold true))
  (when (seq completed-work)
    (println)
    (println (style (messages/t :classified-error/task-completed)
                    :foreground :green))
    (doseq [work completed-work]
      (println (str "  " (style "✅" :foreground :green) " " work)))))

(defn print-task-code-error-header
  []
  (println (style (messages/t :classified-error/task-code-header)
                  :foreground :red :bold true)))

(defn print-external-error-header
  []
  (println (style (messages/t :classified-error/external-header)
                  :foreground :yellow :bold true)))

(defn print-generic-error-header
  []
  (println (style (messages/t :classified-error/generic-header)
                  :foreground :red :bold true)))

(defn print-agent-backend-error-context
  [completed-work]
  (if (seq completed-work)
    (println (messages/t :classified-error/agent-backend-context-success))
    (println (messages/t :classified-error/agent-backend-context))))

(defn print-task-code-error-context
  [completed-work]
  (println (messages/t :classified-error/task-code-context))
  (when (seq completed-work)
    (println)
    (println (messages/t :classified-error/partial-work))
    (doseq [work completed-work]
      (println (str "  ⏸️  " work)))))

(defn print-external-error-context
  [completed-work]
  (println (messages/t :classified-error/external-context))
  (when (seq completed-work)
    (println)
    (println (messages/t :classified-error/partial-work))
    (doseq [work completed-work]
      (println (str "  " (style "✅" :foreground :green) " " work)))))

(defn print-error-header-by-type
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-header completed-work)
    :task-code (print-task-code-error-header)
    :external (print-external-error-header)
    (print-generic-error-header)))

(defn print-error-context
  [error-type completed-work]
  (case error-type
    :agent-backend (print-agent-backend-error-context completed-work)
    :task-code (print-task-code-error-context completed-work)
    :external (print-external-error-context completed-work)
    nil))

(defn print-error-report-url
  [report-url vendor]
  (when report-url
    (println)
    (println (str (style (messages/t :classified-error/report-prefix)
                         :foreground :cyan)
                  vendor ":"))
    (println (str "   " report-url))))

(defn get-retry-recommendation
  [error-type]
  (case error-type
    :task-code (messages/t :classified-error/retry-task-code)
    :external (messages/t :classified-error/retry-external)
    :agent-backend (messages/t :classified-error/retry-agent-backend)
    (messages/t :classified-error/retry-generic)))

(defn print-retry-recommendation
  [should-retry error-type completed-work]
  (println)
  (if should-retry
    (println (str (style (messages/t :classified-error/recommendation-prefix)
                         :foreground :cyan)
                 (get-retry-recommendation error-type)))
    (println (str (style (messages/t :classified-error/no-retry-prefix)
                         :foreground :cyan)
                 (if (seq completed-work)
                   (messages/t :classified-error/no-retry-success)
                   (messages/t :classified-error/no-retry-failure))))))

;------------------------------------------------------------------------------ Layer 2
;; Composite error display

(defn print-classified-error
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
