(ns ai.miniforge.cli.main.commands.pr
  "PR operations using GitHub CLI."
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]))

;------------------------------------------------------------------------------ Layer 1
;; PR commands

(defn pr-list-cmd
  "List PRs using GitHub CLI."
  [opts load-config-fn]
  (let [{:keys [repo config]} opts
        cfg (load-config-fn config)
        repos (if repo [repo] (get-in cfg [:fleet :repos] []))]

    (if (empty? repos)
      (do
        (display/print-error "No repositories specified.")
        (println "Use --repo <owner/repo> or add repos with"
                 (str "'" (app-config/command-string "fleet add") "'")))
      (doseq [r repos]
        (println)
        (println (display/style (str "PRs for " r) :foreground :cyan :bold true))
        (let [result (process/sh "gh" "pr" "list" "--repo" r "--json" "number,title,state,author,createdAt" "--limit" "10")]
          (if (zero? (:exit result))
            (try
              (let [prs (json/parse-string (:out result) true)]
                (if (empty? prs)
                  (println "  No open PRs")
                  (doseq [{:keys [number title state author]} prs]
                    (let [status-style (case state
                                         "OPEN" :green
                                         "MERGED" :magenta
                                         "CLOSED" :red
                                         :white)]
                      (println (str "  #" number " "
                                    (display/style (str "[" state "]") :foreground status-style)
                                    " " title
                                    " (" (:login author "unknown") ")"))))))
              (catch Exception _
                ;; Fallback to simple text output if JSON parsing fails
                (let [result2 (process/sh "gh" "pr" "list" "--repo" r "--limit" "10")]
                  (if (zero? (:exit result2))
                    (println (:out result2))
                    (display/print-error (str "Failed to list PRs: " (:err result2)))))))
            (display/print-error (str "Failed to query GitHub: " (:err result)))))))))

(defn pr-review-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (str "Usage: " (app-config/command-string "pr review <pr-url>")))
      (do
        (display/print-info (str "Reviewing: " url))
        (println "TODO: Implement PR review with agent")))))

(defn pr-respond-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (str "Usage: " (app-config/command-string "pr respond <pr-url>")))
      (do
        (display/print-info (str "Responding to comments on: " url))
        (println "TODO: Implement PR comment response")))))

(defn pr-merge-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (str "Usage: " (app-config/command-string "pr merge <pr-url>")))
      (do
        (display/print-info (str "Merging: " url))
        (println "TODO: Use gh pr merge")))))
