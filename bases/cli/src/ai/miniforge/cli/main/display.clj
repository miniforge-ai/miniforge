(ns ai.miniforge.cli.main.display
  "Terminal styling and error display for CLI output."
  (:require
   [clojure.string :as str]))

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
  (println (style (str "Error: " msg) :foreground :red)))

(defn print-success [msg]
  (println (style msg :foreground :green)))

(defn print-info [msg]
  (println (style msg :foreground :cyan)))

;------------------------------------------------------------------------------ Layer 1
;; Error classification display

(defn print-agent-backend-error-header
  [completed-work]
  (println (style "⚠️  Agent System Error (Not Your Fault!)" :foreground :yellow :bold true))
  (when (seq completed-work)
    (println)
    (println (style "Your task completed successfully:" :foreground :green))
    (doseq [work completed-work]
      (println (str "  " (style "✅" :foreground :green) " " work)))))

(defn print-task-code-error-header
  []
  (println (style "❌ Task Code Error" :foreground :red :bold true)))

(defn print-external-error-header
  []
  (println (style "⚠️  External Service Error" :foreground :yellow :bold true)))

(defn print-generic-error-header
  []
  (println (style "❌ Error" :foreground :red :bold true)))

(defn print-agent-backend-error-context
  [completed-work]
  (if (seq completed-work)
    (println "This is a bug in Claude Code's agent runtime, not in your task or miniforge.\nYour work is complete and safe.")
    (println "This is a bug in Claude Code's agent runtime.")))

(defn print-task-code-error-context
  [completed-work]
  (println "This is an issue with the code being generated or the task specification.")
  (when (seq completed-work)
    (println)
    (println "Partial work completed:")
    (doseq [work completed-work]
      (println (str "  ⏸️  " work)))))

(defn print-external-error-context
  [completed-work]
  (println "This is not an issue with your code or miniforge. The external service\nis temporarily unavailable.")
  (when (seq completed-work)
    (println)
    (println "Partial work completed:")
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
    (println (str (style "📝 Please report this to " :foreground :cyan) vendor ":"))
    (println (str "   " report-url))))

(defn get-retry-recommendation
  [error-type]
  (case error-type
    :task-code "Fix the issue and retry the task."
    :external "Wait a few minutes and retry - this is likely transient."
    :agent-backend "Try again later after the bug is fixed."
    "Check the error and decide if retry is appropriate."))

(defn print-retry-recommendation
  [should-retry error-type completed-work]
  (println)
  (if should-retry
    (println (str (style "🔄 Recommendation: " :foreground :cyan)
                 (get-retry-recommendation error-type)))
    (println (str (style "⚙️  " :foreground :cyan)
                 (if (seq completed-work)
                   "No need to retry - your task succeeded."
                   "Report this bug and try again later.")))))

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
