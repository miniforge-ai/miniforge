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

(defn- first-sentence
  "Extract the first sentence or line from text, capped at max-len chars."
  [text max-len]
  (let [clean (-> (or text "")
                  (str/replace #"\n.*" "")        ;; first line only
                  (str/replace #"\s*-\s.*" "")    ;; strip bullet points
                  (str/replace #"\s*Read source:.*" "") ;; strip "Read source:" hints
                  (str/replace #":\s*$" "")        ;; strip trailing colons
                  (str/replace #"^Create \S+\.\w+ with " "Add ") ;; "Create foo.clj with" → "Add"
                  str/trim)]
    (if (<= (count clean) max-len)
      clean
      (let [truncated (subs clean 0 max-len)
            ;; Cut at last word boundary
            last-space (str/last-index-of truncated " ")]
        (if last-space
          (str (subs truncated 0 last-space) "...")
          (str truncated "..."))))))

;------------------------------------------------------------------------------ Layer 0b
;; Workflow data extraction

(defn extract-review-artifacts
  "Extract review artifacts from workflow artifacts list.
   Returns a seq of review content maps."
  [workflow-artifacts]
  (->> (or workflow-artifacts [])
       (filter #(or (= :review (:type %))
                    (= :review (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))
       (remove nil?)))

(defn extract-test-artifacts
  "Extract test artifacts from workflow artifacts list.
   Returns a seq of test content maps."
  [workflow-artifacts]
  (->> (or workflow-artifacts [])
       (filter #(or (= :test (:type %))
                    (= :test (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))
       (remove nil?)))

;------------------------------------------------------------------------------ Layer 1
;; PR body formatting

(defn- format-file-list
  "Format code artifact files as a markdown list."
  [code-artifacts]
  (let [files (mapcat :code/files code-artifacts)]
    (when (seq files)
      (str/join "\n" (map #(str "- `" (:path %) "` (" (name (or (:action %) :create)) ")") files)))))

(defn- format-gate-results
  "Format gate results from review artifacts as markdown for the test plan.
   Returns a string of gate result lines, or nil if no gate data."
  [review-artifacts]
  (let [reviews (filter #(contains? % :review/gate-results) review-artifacts)]
    (when (seq reviews)
      (let [latest-review (last reviews)
            gate-results (:review/gate-results latest-review)
            passed (:review/gates-passed latest-review)
            failed (:review/gates-failed latest-review)
            total (:review/gates-total latest-review)]
        (when (and total (pos? total))
          (let [header (str "**Quality gates**: " passed "/" total " passed")
                gate-lines (when (seq gate-results)
                             (->> gate-results
                                  (map (fn [g]
                                         (let [gate-name (or (:gate/name g) (:name g) "gate")
                                               status (or (:gate/status g) (:status g) :unknown)
                                               icon (case status
                                                      :passed "✅"
                                                      :failed "❌"
                                                      :skipped "⏭️"
                                                      "❓")]
                                           (str "  - " icon " " gate-name))))
                                  (str/join "\n")))]
            (if gate-lines
              (str header "\n" gate-lines)
              header)))))))

(defn- format-review-summary
  "Extract and format the review summary from review artifacts.
   Returns a formatted string or nil if no review summary available."
  [review-artifacts]
  (let [reviews (filter #(contains? % :review/summary) review-artifacts)]
    (when (seq reviews)
      (let [latest-review (last reviews)
            summary (:review/summary latest-review)
            decision (:review/decision latest-review)]
        (when (and summary (not (str/blank? summary)))
          (let [decision-str (when decision
                               (str "**Decision**: " (name decision) "\n\n"))]
            (str decision-str summary)))))))

(defn- render-pr-body
  "Render a structured PR body from available data.

   Arguments:
   - summary        — concise description for the Summary section
   - file-list      — markdown-formatted file list, or nil
   - file-count     — number of files changed
   - review-summary — formatted review summary string, or nil
   - gate-summary   — formatted gate results string, or nil"
  [summary file-list file-count review-summary gate-summary]
  (str (msg/t :pr/summary-section {:summary summary})
       (when file-list
         (msg/t :pr/changes-section {:file-list file-list}))
       (when (and review-summary (not (str/blank? review-summary)))
         (msg/t :pr/review-section {:review-body review-summary}))
       (let [base-plan (msg/t :pr/test-plan-section)
             gate-block (when (and gate-summary (not (str/blank? gate-summary)))
                          (str (msg/t :pr/gate-results-section {:gate-body gate-summary})
                               "\n\n"))]
         (str base-plan
              (when gate-block gate-block)))
       (msg/t :pr/footer {:file-count file-count
                          :file-label (file-label file-count)})))

;------------------------------------------------------------------------------ Layer 2
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

(defn fallback-release-metadata
  "Generate deterministic release metadata from the task description and artifacts.
   Used when no releaser agent is configured or LLM backend is unavailable.

   Arguments:
   - task-description  — human-readable task description
   - code-artifacts    — seq of code artifact maps with :code/files
   - workflow-data     — optional map with :review-artifacts and :test-artifacts
                         extracted from the workflow state"
  ([task-description code-artifacts]
   (fallback-release-metadata task-description code-artifacts nil))
  ([task-description code-artifacts workflow-data]
   (let [desc (or task-description (default-task-description))
         title (first-sentence desc 70)
         slug (slugify title)
         branch (str "mf/" slug "-" (subs (str (random-uuid)) 0 8))
         file-list (format-file-list code-artifacts)
         file-count (count (mapcat :code/files code-artifacts))
         review-artifacts (:review-artifacts workflow-data)
         review-summary (when (seq review-artifacts)
                          (format-review-summary review-artifacts))
         gate-summary (when (seq review-artifacts)
                        (format-gate-results review-artifacts))
         body (render-pr-body (first-sentence desc 200)
                              file-list file-count
                              review-summary gate-summary)]
     {:release/branch-name branch
      :release/commit-message title
      :release/pr-title title
      :release/pr-body body
      :release/pr-description body})))

(defn generate-release-metadata
  "Generate release metadata using releaser agent, falling back to deterministic
   metadata from the task description when no agent or LLM is available.

   Arguments:
   - releaser         — releaser agent map with :invoke-fn, or nil
   - code-artifacts   — seq of code artifact maps
   - task-description — human-readable task description
   - context          — execution context with :llm-backend etc.
   - logger           — logger instance
   - workflow-data    — optional map with :review-artifacts, :test-artifacts"
  ([releaser code-artifacts task-description context logger]
   (generate-release-metadata releaser code-artifacts task-description context logger nil))
  ([releaser code-artifacts task-description context logger workflow-data]
   (or (invoke-releaser releaser code-artifacts task-description context logger)
       (do
         (when logger
           (log/info logger :release-executor :release/using-fallback-metadata
                     {:message (msg/t :release/using-deterministic-metadata)}))
         (fallback-release-metadata task-description code-artifacts workflow-data)))))
