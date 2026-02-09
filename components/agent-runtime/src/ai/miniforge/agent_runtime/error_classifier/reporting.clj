(ns ai.miniforge.agent-runtime.error-classifier.reporting
  "Vendor reporting and issue tracking for error classification.

   Generates vendor-specific issue reporting URLs with pre-filled templates."
  (:require
   [clojure.string :as str]))

;;------------------------------------------------------------------------------ Layer 0
;; Vendor information

(def vendor-info
  "Vendor information for issue reporting"
  {"Claude Code" {:name "Claude Code"
                  :repo-url "https://github.com/anthropics/claude-code"
                  :issue-template "/issues/new"}
   "miniforge" {:name "Miniforge"
                :repo-url "https://github.com/miniforge-ai/miniforge"
                :issue-template "/issues/new"}
   "External Service" {:name "External Service"
                       :repo-url nil
                       :issue-template nil}})

;;------------------------------------------------------------------------------ Layer 1
;; URL generation

(defn- build-issue-title
  "Build issue title based on error type."
  [error-type]
  (case error-type
    :agent-backend "Agent Backend Error"
    :task-code "Task Error"
    :external "External Service Error"
    "Error"))

(defn- build-issue-body
  "Build issue body with error context."
  [error-context]
  (let [{:keys [message task-id timestamp]} error-context]
    (str "Error: " message
         (when task-id
           (str "\nTask ID: " task-id))
         (when timestamp
           (str "\nTimestamp: " timestamp)))))

(defn get-vendor-report-url
  "Generate vendor-specific issue reporting URL with pre-filled context.

   Arguments:
     error-type - Classification keyword (:agent-backend, :task-code, :external)
     vendor     - Vendor name string
     error-context - Map with :message, :task-id, :timestamp, etc.

   Returns: String URL for issue reporting (or nil for external errors)"
  [error-type vendor error-context]
  (let [vendor-data (get vendor-info vendor)]
    (when (and vendor-data (:repo-url vendor-data))
      (let [title (build-issue-title error-type)
            title-param (str/replace title " " "+")
            body (build-issue-body error-context)]
        (str (:repo-url vendor-data)
             (:issue-template vendor-data)
             "?title=" title-param
             "&body=" (java.net.URLEncoder/encode body "UTF-8"))))))

;; Alias for backward compatibility
(def generate-report-url get-vendor-report-url)
