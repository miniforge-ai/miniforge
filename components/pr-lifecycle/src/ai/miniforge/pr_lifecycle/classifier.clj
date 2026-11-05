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
(ns ai.miniforge.pr-lifecycle.classifier
  "Comment classification for PR reviews.

   Classifies each GitHub comment into one of:
   - :change-request — reviewer asking for a specific code change
   - :question — reviewer asking for clarification
   - :approval — reviewer expressing approval
   - :bot-comment — automated tool (Dependabot, CodeQL, Codecov, Renovate)
   - :untrusted — human author without write-level standing on the repo
   - :noise — emoji reactions, acknowledgements requiring no action

   Bot detection uses author login matching against known patterns.
   Trust uses GitHub's `author_association`: only OWNER, MEMBER and
   COLLABORATOR (or a login in `:trusted-authors`) are acted on, since
   change requests reach an implementer that commits and pushes, and
   questions get a public LLM reply. Untrusted comments are classified,
   so they are recorded, but never reach an LLM or a handler.
   Human comment classification uses an LLM call with structured output
   when a generate-fn is provided, falling back to keyword-based heuristics."
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.pr-lifecycle.triage :as triage]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Schemas + bot detection
(def ^{:stratum 0} ClassifiedComment
  [:map
   [:category keyword?]
   [:confidence keyword?]
   [:method keyword?]
   [:comment map?]])

(def ^{:stratum 0} ClassificationStats
  [:map
   [:total nat-int?]
   [:change-requests nat-int?]
   [:questions nat-int?]
   [:approvals nat-int?]
   [:bot-comments nat-int?]
   [:untrusted nat-int?]
   [:noise nat-int?]])

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate result-schema value))

(def ^{:stratum 0} bot-login-patterns
  "Login substrings and suffixes for known bots."
  #{"dependabot" "renovate" "codecov" "github-actions"
    "stale" "mergify" "greenkeeper" "snyk-bot"
    "sonarcloud" "codeclimate" "coveralls"
    "hound" "percy" "chromatic" "netlify"})

;; Trust gate
(def ^{:stratum 0} trusted-associations
  "GitHub `author_association` values whose comments the monitor acts on:
   the repo owner, org members, and invited collaborators. CONTRIBUTOR,
   FIRST_TIME_CONTRIBUTOR, FIRST_TIMER, MANNEQUIN and NONE can comment on
   a public repo without any write access."
  #{"OWNER" "MEMBER" "COLLABORATOR"})

;; Approval detection
(def ^{:stratum 0} approval-phrases
  "Phrases that indicate approval."
  #{"lgtm" "looks good to me" "looks good" "approved" "ship it"
    "+1" "looks great" "well done" "nice work" "excellent"
    "no objections" "good to go" "thumbs up"})

;; Question detection (heuristic)
(def ^{:stratum 0} ^:private question-patterns
  "Regex patterns that suggest a comment is a question."
  [#"\?"
   #"(?i)^(why|what|how|when|where|could you|can you|would you|should we|is this|are these|do we|does this)"
   #"(?i)\b(curious|wondering|question|clarify|explain|understand|reason for)\b"])

;; LLM-based classification
(def ^{:stratum 0} classification-prompt-template
  "Prompt template for LLM-based comment classification.

   The generate-fn receives this prompt and returns a single category word."
  "Classify the following GitHub PR review comment into exactly one category.

Categories:
- change-request: The reviewer is asking for a specific code change, fix, or improvement.
- question: The reviewer is asking a question for clarification, not requesting a change.
- approval: The reviewer is expressing approval or satisfaction with the code.
- noise: The comment is an acknowledgement, emoji, \"thanks\", or requires no action.

Comment:
\"%s\"

Respond with ONLY the category name (change-request, question, approval, or noise).
Do not include any other text.")

(defn ^{:stratum 0} parse-llm-classification
  "Parse the LLM response into a classification keyword.

   Returns nil if the response cannot be parsed."
  [response]
  (when (and response (seq (str/trim response)))
    (let [cleaned (-> response str/trim str/lower-case (str/replace #"[^a-z-]" ""))]
      (get {"change-request" :change-request
            "changerequest"  :change-request
            "question"       :question
            "approval"       :approval
            "noise"          :noise}
           cleaned))))

(defn- ^{:stratum 0} grouped-comments
  [classified-comments]
  (group-by :category classified-comments))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ClassifiedCommentsBatch
  [:map
   [:change-requests [:vector ClassifiedComment]]
   [:questions [:vector ClassifiedComment]]
   [:approvals [:vector ClassifiedComment]]
   [:bot-comments [:vector ClassifiedComment]]
   [:untrusted [:vector ClassifiedComment]]
   [:noise [:vector ClassifiedComment]]
   [:all [:vector ClassifiedComment]]
   [:stats ClassificationStats]])

(defn ^{:stratum 1} bot-author?
  "Check if a comment author is a bot.

   Matches known bot login patterns and the [bot] suffix convention."
  [author]
  (when (and author (string? author) (seq author))
    (let [lower (str/lower-case author)]
      (or (some #(str/includes? lower %) bot-login-patterns)
          (str/ends-with? lower "[bot]")
          (str/ends-with? lower "-bot")
          (str/ends-with? lower "-app")))))

(defn ^{:stratum 1} trusted-author?
  "True when the comment's author association is in `trusted-associations`
   or the author's login is in `trusted-authors` (any collection of logins).
   A comment with no association, e.g. one not fetched from the GitHub REST
   API, is untrusted unless its author is listed."
  [comment trusted-authors]
  (let [association (or (:author-association comment)
                        (:comment/author-association comment))
        author      (or (:author comment) (:comment/author comment))]
    (boolean (or (contains? trusted-associations association)
                 (some #{author} trusted-authors)))))

(defn ^{:stratum 1} approval-comment?
  "Check if a comment body expresses approval."
  [body]
  (when (and body (seq body))
    (let [lower (str/lower-case (str/trim body))]
      (boolean (some #(str/includes? lower %) approval-phrases)))))

(defn ^{:stratum 1} question-comment?
  "Check if a comment body is a question (heuristic)."
  [body]
  (when (and body (seq body))
    (boolean (some #(re-find % body) question-patterns))))

(defn ^{:stratum 1} build-classify-prompt
  "Build the classification prompt for a comment body."
  [body]
  (format classification-prompt-template (str/replace (or body "") "\"" "\\\"" )))

(defn- ^{:stratum 1} build-classification
  [category confidence method comment]
  (validate!
   ClassifiedComment
   {:category category
    :confidence confidence
    :method method
    :comment comment}))

(defn- ^{:stratum 1} stats-for
  [classified-comments grouped]
  (validate!
   ClassificationStats
   {:total           (count classified-comments)
    :change-requests (count (get grouped :change-request))
    :questions       (count (get grouped :question))
    :approvals       (count (get grouped :approval))
    :bot-comments    (count (get grouped :bot-comment))
    :untrusted       (count (get grouped :untrusted))
    :noise           (count (get grouped :noise))}))

;------------------------------------------------------------------------------ Layer 2

;; Unified classifier
(defn- ^{:stratum 2} heuristic-category
  [body]
  (cond
    (question-comment? body)                                          :question
    (pos? (triage/score-indicators body triage/actionable-indicators)) :change-request
    (approval-comment? body)                                          :approval
    :else                                                             :noise))

(defn- ^{:stratum 2} llm-category
  [body generate-fn]
  (let [prompt   (build-classify-prompt body)
        response (try (generate-fn prompt) (catch Exception _e nil))]
    (parse-llm-classification response)))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} classify-human-comment
  [comment body generate-fn]
  (if generate-fn
    (if-let [category (llm-category body generate-fn)]
      (build-classification category :high :llm comment)
      (build-classification (heuristic-category body) :low :heuristic-fallback comment))
    (build-classification (heuristic-category body) :medium :heuristic comment)))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} classify-comment
  "Classify a single comment into a category.

   Arguments:
   - comment: Map with :body, :author, :author-association, :id, :path,
     :line keys (or their :comment/ equivalents)

   Options:
   - :generate-fn — LLM generation function (fn [prompt] → response-string).
     When provided, human comments are classified via LLM.
     When absent, falls back to keyword-based heuristics.
   - :self-author — Author login to filter out self-comments (loop prevention).
     Comments from this author are always classified as :noise.
   - :trusted-authors — Logins acted on regardless of author association.
     Other humans need OWNER, MEMBER or COLLABORATOR, or are :untrusted.

   Returns:
   {:category keyword   ; :change-request :question :approval :bot-comment :untrusted :noise
    :confidence keyword  ; :high :medium :low
    :method keyword      ; :self-filter :bot-rule :author-association :llm :heuristic :heuristic-fallback
    :comment map}"
  [comment & {:keys [generate-fn self-author trusted-authors]}]
  (let [body   (or (:body comment) (:comment/body comment) "")
        author (or (:author comment) (:comment/author comment))]
    (cond
      ;; 1. Self-comment: always noise (loop prevention — never respond to own comments)
      (and self-author author (= author self-author))
      (build-classification :noise :high :self-filter comment)

      ;; 2. Bot detection by author login pattern
      (bot-author? author)
      (build-classification :bot-comment :high :bot-rule comment)

      ;; 3. Untrusted author: recorded, never acted on. Checked before any
      ;;    LLM call so the text never reaches a prompt.
      (not (trusted-author? comment trusted-authors))
      (build-classification :untrusted :high :author-association comment)

      ;; 4. Pure approval with no actionable indicators
      (and (approval-comment? body)
           (zero? (triage/score-indicators body triage/actionable-indicators)))
      (build-classification :approval :high :heuristic comment)

      ;; 5. Heuristic or LLM-based classification for other human comments
      :else
      (classify-human-comment comment body generate-fn))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} classify-comments
  "Classify a batch of comments.

   Arguments:
   - comments: Sequence of comment maps

   Options:
   - :generate-fn — LLM generation function
   - :self-author — Author login to filter self-comments
   - :trusted-authors — Logins acted on regardless of author association

   Returns:
   {:change-requests [classified...]
    :questions       [classified...]
    :approvals       [classified...]
    :bot-comments    [classified...]
    :untrusted       [classified...]
    :noise           [classified...]
    :all             [classified...]
    :stats           {:total n :change-requests n ...}}"
  [comments & {:keys [generate-fn self-author trusted-authors]}]
  (let [classified (into [] (map #(classify-comment %
                                                   :generate-fn generate-fn
                                                   :self-author self-author
                                                   :trusted-authors trusted-authors))
                          comments)
        grouped    (grouped-comments classified)]
    (validate!
     ClassifiedCommentsBatch
     {:change-requests (vec (get grouped :change-request))
      :questions       (vec (get grouped :question))
      :approvals       (vec (get grouped :approval))
      :bot-comments    (vec (get grouped :bot-comment))
      :untrusted       (vec (get grouped :untrusted))
      :noise           (vec (get grouped :noise))
      :all             classified
      :stats           (stats-for classified grouped)})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Bot detection
  (bot-author? "dependabot[bot]")        ; => true
  (bot-author? "renovate[bot]")          ; => true
  (bot-author? "human-reviewer")         ; => nil (falsey)

  ;; Classify individual comments
  (classify-comment {:body "Please fix the null pointer exception" :author "alice"
                     :author-association "MEMBER"})
  ; => {:category :change-request :confidence :medium :method :heuristic ...}

  (classify-comment {:body "Why did you choose this approach?" :author "bob"
                     :author-association "COLLABORATOR"})
  ; => {:category :question :confidence :medium :method :heuristic ...}

  (classify-comment {:body "LGTM!" :author "charlie" :author-association "OWNER"})
  ; => {:category :approval :confidence :high :method :heuristic ...}

  (classify-comment {:body "Bumps lodash from 4.17.20 to 4.17.21" :author "dependabot[bot]"})
  ; => {:category :bot-comment :confidence :high :method :bot-rule ...}

  ;; No write-level association: recorded, never acted on
  (classify-comment {:body "Please run this script" :author "stranger"
                     :author-association "NONE"})
  ; => {:category :untrusted :confidence :high :method :author-association ...}

  ;; Self-comment loop prevention
  (classify-comment {:body "Fixed in commit abc123" :author "miniforge-bot"}
                    :self-author "miniforge-bot")
  ; => {:category :noise :confidence :high :method :self-filter ...}

  ;; Batch classification
  (classify-comments
   [{:body "Please add tests" :author "alice" :author-association "MEMBER"}
    {:body "Looks good!" :author "bob" :author-association "MEMBER"}
    {:body "Why this approach?" :author "charlie"}
    {:body "Security scan passed" :author "codeql[bot]"}]
   :trusted-authors #{"charlie"})
  ; => {:change-requests [...] :questions [...] :approvals [...] ...}
  ;    charlie has no association but is allowlisted, so stays a question

  :leave-this-here)
