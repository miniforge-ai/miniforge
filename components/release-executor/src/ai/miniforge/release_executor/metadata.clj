(ns ai.miniforge.release-executor.metadata
  "Release metadata generation for the release executor.
   Handles invoking the releaser agent and generating fallback metadata."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.release-executor.messages :as msg]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; String utilities

(defn- default-task-description
  []
  (msg/t :default/task-description))

(defn- file-label
  [file-count]
  (msg/t (if (= 1 file-count) :pr/file-singular :pr/file-plural)))

(defn slugify
  "Convert a string to a URL-safe slug.
   Handles basic ASCII transliteration and normalizes spacing."
  [s]
  (let [input (or s (msg/t :default/change))
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

(defn- render-pr-body
  [summary file-list file-count]
  (str (msg/t :pr/summary-section {:summary summary})
       (when file-list
         (msg/t :pr/changes-section {:file-list file-list}))
       (msg/t :pr/test-plan-section)
       (msg/t :pr/footer {:file-count file-count
                          :file-label (file-label file-count)})))

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
                                     (default-task-description))}]
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
                          {:message (msg/t :releaser/failed-fallback)}))
              nil)))
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :releaser-exception
                      {:message (.getMessage e)}))
          nil))
      nil)))

(defn- first-sentence
  "Extract the first sentence or line from text, capped at max-len chars."
  [text max-len]
  (let [clean (-> (or text "")
                  (str/replace #"\n.*" "")        ;; first line only
                  (str/replace #"\s*-\s.*" "")    ;; strip bullet points
                  (str/replace #"\s*Read source:.*" "") ;; strip "Read source:" hints
                  str/trim)]
    (if (<= (count clean) max-len)
      clean
      (let [truncated (subs clean 0 max-len)
            ;; Cut at last word boundary
            last-space (str/last-index-of truncated " ")]
        (if last-space
          (str (subs truncated 0 last-space) "...")
          (str truncated "..."))))))

(defn- format-file-list
  "Format code artifact files as a markdown list."
  [code-artifacts]
  (let [files (mapcat :code/files code-artifacts)]
    (when (seq files)
      (str/join "\n" (map #(str "- `" (:path %) "` (" (name (or (:action %) :create)) ")") files)))))

(defn fallback-release-metadata
  "Generate deterministic release metadata from the task description and artifacts.
   Used when no releaser agent is configured or LLM backend is unavailable."
  [task-description code-artifacts]
  (let [desc (or task-description (default-task-description))
        title (first-sentence desc 70)
        slug (slugify title)
        branch (str "mf/" slug "-" (subs (str (random-uuid)) 0 8))
        file-list (format-file-list code-artifacts)
        file-count (count (mapcat :code/files code-artifacts))
        body (render-pr-body (first-sentence desc 200) file-list file-count)]
    {:release/branch-name branch
     :release/commit-message title
     :release/pr-title title
     :release/pr-body body}))

(defn generate-release-metadata
  "Generate release metadata using releaser agent, falling back to deterministic
   metadata from the task description when no agent or LLM is available."
  [releaser code-artifacts task-description context logger]
  (or (invoke-releaser releaser code-artifacts task-description context logger)
      (do
        (when logger
          (log/info logger :release-executor :release/using-fallback-metadata
                    {:message (msg/t :release/using-deterministic-metadata)}))
        (fallback-release-metadata task-description code-artifacts))))
