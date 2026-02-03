(ns ai.miniforge.release-executor.metadata
  "Release metadata generation for the release executor.
   Handles invoking the releaser agent and generating fallback metadata."
  (:require
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; String utilities

(defn- slugify
  "Convert a string to a URL-safe slug.
   Handles basic ASCII transliteration and normalizes spacing."
  [s]
  (let [input (or s "change")
        ;; Basic ASCII transliteration for common characters
        transliterated (-> input
                           (str/replace #"[àáâãäå]" "a")
                           (str/replace #"[èéêë]" "e")
                           (str/replace #"[ìíîï]" "i")
                           (str/replace #"[òóôõö]" "o")
                           (str/replace #"[ùúûü]" "u")
                           (str/replace #"[ñ]" "n")
                           (str/replace #"[ç]" "c")
                           (str/replace #"[ß]" "ss"))
        slug (-> transliterated
                 str/lower-case
                 (str/replace #"[^a-z0-9\s-]" "")
                 (str/replace #"\s+" "-")
                 (str/replace #"-+" "-")
                 (str/replace #"^-|-$" ""))]
    (subs slug 0 (min 40 (count slug)))))

;------------------------------------------------------------------------------ Layer 1
;; Releaser agent integration

(defn invoke-releaser
  "Invoke the releaser agent to generate release metadata.
   Falls back to nil if agent fails (caller should use fallback)."
  [releaser code-artifacts task-description context logger]
  (let [llm-backend (:llm-backend context)
        first-artifact (first code-artifacts)
        input {:code-artifact first-artifact
               :task-description (or task-description
                                     (:code/summary first-artifact)
                                     "implement changes")}]
    (if (and releaser llm-backend)
      (try
        (let [result ((:invoke-fn releaser)
                      (assoc context :llm-backend llm-backend)
                      input)]
          (when logger
            (log/debug logger :release-executor :releaser-invoked
                       {:data {:status (:status result)}}))
          (if (= :success (:status result))
            (:output result)
            (do
              (when logger
                (log/warn logger :release-executor :releaser-failed-fallback
                          {:message "Releaser agent failed, using fallback"}))
              nil)))
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :releaser-exception
                      {:message (.getMessage e)}))
          nil))
      nil)))

(defn make-fallback-release-metadata
  "Generate fallback release metadata when releaser agent is unavailable."
  [code-artifacts task-description]
  (let [first-artifact (first code-artifacts)
        files (:code/files first-artifact)
        summary (or (:code/summary first-artifact) "code changes")
        task-desc (or task-description summary)
        slug (slugify task-desc)]
    {:release/id (random-uuid)
     :release/branch-name (str "feature/" slug)
     :release/commit-message (str "feat: " task-desc)
     :release/pr-title (str "feat: " (subs task-desc 0 (min 60 (count task-desc))))
     :release/pr-description (str "## Summary\n" task-desc "\n\n"
                                  "## Changes\n"
                                  (if files
                                    (str/join "\n" (map #(str "- " (name (:action %)) " `" (:path %) "`") files))
                                    "See commits for details"))
     :release/created-at (java.util.Date.)}))

(defn generate-release-metadata
  "Generate release metadata using releaser agent or fallback.
   Returns release metadata map."
  [releaser code-artifacts task-description context logger]
  (or (invoke-releaser releaser code-artifacts task-description context logger)
      (make-fallback-release-metadata code-artifacts task-description)))
