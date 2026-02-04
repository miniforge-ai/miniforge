(ns ai.miniforge.pr-lifecycle.triage
  "Comment triage for PR reviews.

   Classifies comments as actionable or non-actionable to determine
   which require automated fixes."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Comment classification

(def actionable-indicators
  "Keywords/phrases that indicate an actionable comment."
  #{;; Direct requests
    "please" "should" "must" "need to" "needs to" "required"
    "change" "fix" "update" "modify" "add" "remove" "delete"
    ;; Problems identified
    "bug" "error" "wrong" "incorrect" "broken" "fails" "failing"
    "issue" "problem" "missing" "undefined" "null" "exception"
    ;; Security/quality
    "security" "vulnerability" "unsafe" "injection" "xss"
    "performance" "memory leak" "race condition"
    ;; Design/architecture
    "violation" "constraint" "requirement" "spec"})

(def non-actionable-indicators
  "Keywords/phrases that indicate a non-actionable comment."
  #{;; Approval/praise
    "lgtm" "looks good" "great" "nice" "well done" "excellent"
    "approved" "ship it" "+1" "thumbs up"
    ;; Questions already addressed
    "nvm" "never mind" "figured it out" "resolved"
    ;; Stylistic only (when formatter handles)
    "nitpick" "nit:" "minor:" "optional:"
    ;; Information only
    "fyi" "for your information" "just noting" "reminder"})

(defn- score-indicators
  "Score text against a set of indicators.
   Returns count of matching indicators."
  [text indicators]
  (let [lower-text (str/lower-case (or text ""))]
    (->> indicators
         (filter #(str/includes? lower-text %))
         count)))

;------------------------------------------------------------------------------ Layer 0
;; Comment structure

(defn parse-comment
  "Parse a raw comment into structured form.

   Input can be:
   - String (plain comment text)
   - Map with :body :author :path :line keys

   Returns structured comment map."
  [raw]
  (if (string? raw)
    {:comment/body raw
     :comment/author nil
     :comment/path nil
     :comment/line nil}
    {:comment/body (:body raw)
     :comment/author (:author raw)
     :comment/path (:path raw)
     :comment/line (:line raw)}))

;------------------------------------------------------------------------------ Layer 1
;; Triage logic

(defn classify-comment
  "Classify a comment as actionable or non-actionable.

   Arguments:
   - comment: Comment map or string

   Options:
   - :threshold - Actionable score threshold (default 1)
   - :author-whitelist - Set of authors whose comments are always actionable

   Returns {:actionable? bool :reason keyword :score int :indicators [...]}"
  [comment & {:keys [threshold author-whitelist]
              :or {threshold 1 author-whitelist #{}}}]
  (let [parsed (parse-comment comment)
        body (:comment/body parsed)
        author (:comment/author parsed)

        ;; Check author whitelist first
        whitelisted? (contains? author-whitelist author)

        ;; Score against indicators
        actionable-score (score-indicators body actionable-indicators)
        non-actionable-score (score-indicators body non-actionable-indicators)

        ;; Collect matching indicators for explanation
        actionable-matches (->> actionable-indicators
                                (filter #(str/includes?
                                          (str/lower-case (or body "")) %))
                                vec)
        non-actionable-matches (->> non-actionable-indicators
                                    (filter #(str/includes?
                                              (str/lower-case (or body "")) %))
                                    vec)

        ;; Determine classification
        actionable? (or whitelisted?
                        (and (>= actionable-score threshold)
                             (> actionable-score non-actionable-score)))]

    {:actionable? actionable?
     :reason (cond
               whitelisted? :whitelisted-author
               (and actionable?
                    (> actionable-score non-actionable-score)) :actionable-indicators
               (> non-actionable-score 0) :non-actionable-indicators
               (zero? actionable-score) :no-indicators
               :else :insufficient-score)
     :scores {:actionable actionable-score
              :non-actionable non-actionable-score}
     :indicators {:actionable actionable-matches
                  :non-actionable non-actionable-matches}
     :comment parsed}))

(defn triage-comments
  "Triage a collection of comments.

   Arguments:
   - comments: Sequence of comments (strings or maps)

   Options:
   - :policy - :actionable-only (default) :all :none
   - :threshold - Actionable score threshold
   - :author-whitelist - Authors whose comments are always actionable

   Returns {:actionable [...] :non-actionable [...] :stats {...}}"
  [comments & {:keys [policy threshold author-whitelist]
               :or {policy :actionable-only threshold 1 author-whitelist #{}}}]
  (let [classified (map #(classify-comment % :threshold threshold
                                           :author-whitelist author-whitelist)
                        comments)
        actionable (filter :actionable? classified)
        non-actionable (remove :actionable? classified)]
    {:actionable (case policy
                   :actionable-only (vec actionable)
                   :all (vec classified)
                   :none [])
     :non-actionable (vec non-actionable)
     :stats {:total (count comments)
             :actionable-count (count actionable)
             :non-actionable-count (count non-actionable)
             :policy policy}}))

;------------------------------------------------------------------------------ Layer 2
;; CI failure triage

(defn parse-ci-failure
  "Parse CI failure logs into structured actionable items.

   Arguments:
   - logs: CI log output (string or lines)

   Returns {:test-failures [...] :lint-errors [...] :build-errors [...]
            :actionable-summary string}"
  [logs]
  (let [lines (if (string? logs)
                (str/split-lines logs)
                logs)
        _lower-lines (map str/lower-case lines)

        ;; Extract test failures
        test-failures (->> lines
                           (filter #(or (str/includes? % "FAIL")
                                        (str/includes? % "ERROR")
                                        (str/includes? % "AssertionError")
                                        (re-find #"expected:.*actual:" %)))
                           (take 20)
                           vec)

        ;; Extract lint errors
        lint-errors (->> lines
                         (filter #(or (str/includes? % "warning:")
                                      (str/includes? % "error:")
                                      (re-find #":\d+:\d+:" %)))
                         (take 20)
                         vec)

        ;; Extract build errors
        build-errors (->> lines
                          (filter #(or (str/includes? % "BUILD FAILED")
                                       (str/includes? % "compilation failed")
                                       (str/includes? % "Could not resolve")))
                          vec)]
    {:test-failures test-failures
     :lint-errors lint-errors
     :build-errors build-errors
     :actionable-summary (str/join "\n"
                                   (concat
                                    (when (seq test-failures)
                                      [(str (count test-failures) " test failure(s)")])
                                    (when (seq lint-errors)
                                      [(str (count lint-errors) " lint error(s)")])
                                    (when (seq build-errors)
                                      [(str (count build-errors) " build error(s)")])))}))

(defn extract-failing-files
  "Extract file paths mentioned in CI failures.

   Returns set of file paths."
  [ci-failure]
  (let [all-lines (concat (:test-failures ci-failure)
                          (:lint-errors ci-failure)
                          (:build-errors ci-failure))
        ;; Common file path patterns
        path-patterns [#"([a-zA-Z0-9_/\-\.]+\.(clj|cljs|cljc|edn|java|py|js|ts))"
                       #"at\s+([a-zA-Z0-9_/\-\.]+):\d+"
                       #"in\s+file\s+([a-zA-Z0-9_/\-\.]+)"]]
    (->> all-lines
         (mapcat (fn [line]
                   (mapcat #(re-seq % line) path-patterns)))
         (map second)
         (filter some?)
         (remove #(str/starts-with? % "java."))
         set)))

;------------------------------------------------------------------------------ Layer 3
;; Review request triage

(defn extract-requested-changes
  "Extract specific requested changes from review comments.

   Returns sequence of {:file path :change description :original comment}"
  [actionable-comments]
  (->> actionable-comments
       (map (fn [{:keys [comment]}]
              (let [body (:comment/body comment)
                    path (:comment/path comment)
                    line (:comment/line comment)]
                {:file path
                 :line line
                 :change body
                 :original comment})))
       (filter :file) ; Only comments with file context
       vec))

(defn group-changes-by-file
  "Group requested changes by file for efficient processing."
  [requested-changes]
  (->> requested-changes
       (group-by :file)
       (map (fn [[file changes]]
              {:file file
               :changes (vec changes)
               :lines (set (keep :line changes))}))
       vec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Classify individual comments
  (classify-comment "LGTM, looks good!")
  ; => {:actionable? false :reason :non-actionable-indicators ...}

  (classify-comment "Please fix the null pointer exception in foo.clj")
  ; => {:actionable? true :reason :actionable-indicators ...}

  (classify-comment "This is a security vulnerability - SQL injection possible")
  ; => {:actionable? true :reason :actionable-indicators ...}

  ;; Triage a batch
  (triage-comments
   ["Great work!"
    "Please add tests for this function"
    "LGTM"
    "Bug: this crashes when input is nil"])
  ; => {:actionable [...] :non-actionable [...] :stats {:actionable-count 2 ...}}

  ;; Parse CI failures
  (parse-ci-failure
   "FAIL in (test-foo)
    expected: 1
    actual: 2
    at foo_test.clj:42

    BUILD FAILED")
  ; => {:test-failures [...] :build-errors [...] ...}

  ;; Extract files from failures
  (extract-failing-files
   {:test-failures ["at foo_test.clj:42"]
    :lint-errors ["src/bar.clj:10:5: error: undefined symbol"]
    :build-errors []})
  ; => #{"foo_test.clj" "src/bar.clj"}

  :leave-this-here)
