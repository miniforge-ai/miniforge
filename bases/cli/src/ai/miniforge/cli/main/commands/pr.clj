(ns ai.miniforge.cli.main.commands.pr
  "PR operations using GitHub CLI."
  (:require
   [babashka.process :as process]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

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
        (display/print-error (messages/t :pr/no-repos))
        (println (messages/t :pr/no-repos-hint {:command (app-config/command-string "fleet add")})))
      (doseq [r repos]
        (println)
        (println (display/style (messages/t :pr/header {:repo r}) :foreground :cyan :bold true))
        (let [result (process/sh "gh" "pr" "list" "--repo" r "--json" "number,title,state,author,createdAt" "--limit" "10")]
          (if (zero? (:exit result))
            (try
              (let [prs (json/parse-string (:out result) true)]
                (if (empty? prs)
                  (println (messages/t :pr/no-open))
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
                    (display/print-error (messages/t :pr/list-failed {:error (:err result2)}))))))
            (display/print-error (messages/t :pr/query-failed {:error (:err result)}))))))))

(defn pr-review-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/review-usage {:command (app-config/command-string "pr review <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/reviewing {:url url}))
        (println (messages/t :pr/review-todo))))))

(defn pr-respond-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/respond-usage {:command (app-config/command-string "pr respond <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/responding {:url url}))
        (println (messages/t :pr/respond-todo))))))

(defn pr-merge-cmd
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (display/print-error (messages/t :pr/merge-usage {:command (app-config/command-string "pr merge <pr-url>")}))
      (do
        (display/print-info (messages/t :pr/merging {:url url}))
        (println (messages/t :pr/merge-todo))))))
