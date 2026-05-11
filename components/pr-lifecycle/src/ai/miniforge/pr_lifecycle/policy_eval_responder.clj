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

(ns ai.miniforge.pr-lifecycle.policy-eval-responder
  "N13 §2.5 Comment Response Agent — structured-payload path.

   Existing `pr-lifecycle.responder` handles general human review
   comments via LLM. This namespace is the deterministic path for
   the one bot we own: `miniforge-policy-evaluator[bot]` (the
   N13 §2.2 Standards Reviewer).

   Each such comment carries an embedded `:comment/payload` EDN block
   per N13 §2.3. We parse it, apply the `:violation/suggested-fix`
   in-place at the comment's `(path, line)`, commit + push, then
   reply on each fixed thread with the commit SHA and resolve the
   conversation.

   v0 scope:
   - Single-line `:violation/suggested-fix` (the common case for
     pack-rule violations like 'replace `(get m k default)` with
     `(or (:k m) default)' style hints).
   - Multi-line and patch-hunk-shaped fixes are deferred to v1 with
     a typed `:policy-eval/multi-line-not-supported` skip.
   - Non-fixable comments (`:violation/auto-fixable? false`) are
     escalated as attention items, not auto-applied.

   Layer 0: marker / extraction helpers (delegates to compliance-scanner)
   Layer 1: per-fix planning + application (pure)
   Layer 2: fix-set materialization (file I/O)
   Layer 3: git commit + push + reply-resolve orchestration"
  (:require
   [ai.miniforge.compliance-scanner.interface :as scanner]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Comment classification + payload extraction

(def policy-eval-author
  "GitHub login the renderer posts under (per
   `compliance-scanner.comments/violation->comment`)."
  "miniforge-policy-evaluator[bot]")

(defn policy-eval-comment?
  "True when `comment` was posted by the N13 Standards Reviewer
   (matched by author login). Comments from other bots / humans are
   handled by the general LLM responder, not by this module."
  [comment]
  (= policy-eval-author (:comment/author comment)))

(defn extract-policy-payload
  "Return the embedded `:comment/payload` map from `comment`'s body,
   or nil. Delegates to `compliance-scanner.interface/extract-comment-payload`
   so the round-trip is the inverse of the renderer."
  [comment]
  (scanner/extract-comment-payload (:comment/body comment)))

;------------------------------------------------------------------------------ Layer 1
;; Per-fix planning (pure)

(defn- multi-line?
  "True when the suggested-fix spans more than one line. v0 skips
   these because applying a multi-line replacement requires knowing
   the original span, which the comment doesn't carry directly."
  [suggested-fix]
  (and (string? suggested-fix)
       (str/includes? suggested-fix "\n")))

(defn classify-fix
  "Pure: decide what to do with a single policy-eval comment.
   Returns `{:action :apply | :escalate | :skip
             :reason  <keyword>
             :payload <payload-map-or-nil>}`."
  [comment]
  (let [payload (extract-policy-payload comment)]
    (cond
      (nil? payload)
      {:action :skip :reason :no-payload :payload nil}

      (false? (:violation/auto-fixable? payload))
      {:action :escalate
       :reason :violation/auto-fixable?-false
       :payload payload}

      (str/blank? (:violation/suggested-fix payload))
      {:action :escalate
       :reason :no-suggested-fix
       :payload payload}

      (multi-line? (:violation/suggested-fix payload))
      {:action :escalate
       :reason :policy-eval/multi-line-not-supported
       :payload payload}

      :else
      {:action :apply :reason :ok :payload payload})))

(defn plan-fixes
  "Pure: walk a vector of comments, classify each, and partition into
   `{:to-apply [...] :to-escalate [...] :to-skip [...]}`. Each entry
   carries the original comment + its classification."
  [comments]
  (reduce
   (fn [acc comment]
     (let [c (classify-fix comment)
           bucket (case (:action c)
                    :apply    :to-apply
                    :escalate :to-escalate
                    :skip     :to-skip)]
       (update acc bucket conj
               {:comment comment
                :payload (:payload c)
                :reason  (:reason c)})))
   {:to-apply [] :to-escalate [] :to-skip []}
   comments))

;------------------------------------------------------------------------------ Layer 2
;; Fix-set materialization (file I/O)

(defn- read-lines
  [path]
  (vec (str/split-lines (slurp path))))

(defn- write-lines!
  [path lines]
  (spit path (str/join "\n" lines)))

(defn apply-single-line-replacement!
  "Replace line `line-number` (1-indexed, matching GitHub comment
   line semantics) in `worktree-path/relative-path` with
   `replacement`. Returns DAG result with `{:path :line :before :after}`
   on success.

   Fails with typed errors when:
   - the file doesn't exist (`:policy-eval/file-not-found`)
   - the line number is out of range (`:policy-eval/line-out-of-range`)
   - the read or write throws (`:policy-eval/io-failed`)"
  [worktree-path relative-path line-number replacement]
  (let [abs (str (fs/path worktree-path relative-path))]
    (try
      (cond
        (not (fs/exists? abs))
        (dag/err :policy-eval/file-not-found
                 (str "file " abs " not present in worktree")
                 {:path relative-path})

        :else
        (let [lines (read-lines abs)
              idx   (dec line-number)]
          (cond
            (or (neg? idx) (>= idx (count lines)))
            (dag/err :policy-eval/line-out-of-range
                     (str "line " line-number " out of range (file has "
                          (count lines) " lines)")
                     {:path relative-path :line line-number :file-lines (count lines)})

            :else
            (let [before (nth lines idx)
                  after  replacement
                  next-lines (assoc lines idx after)]
              (write-lines! abs next-lines)
              (dag/ok {:path relative-path :line line-number
                       :before before :after after})))))
      (catch Throwable e
        (dag/err :policy-eval/io-failed
                 (str "failed to apply fix to " abs ": " (.getMessage e))
                 {:path relative-path :line line-number})))))

(defn materialize-fix!
  "Apply one planned `:to-apply` entry's suggested-fix to disk.
   Returns the DAG result from `apply-single-line-replacement!`
   decorated with the originating comment-id for traceability."
  [worktree-path entry]
  (let [{:keys [comment payload]} entry
        path (:comment/path comment)
        line (:comment/line comment)
        fix  (:violation/suggested-fix payload)
        r    (apply-single-line-replacement! worktree-path path line fix)]
    (if (dag/ok? r)
      (dag/ok (assoc (:data r) :comment/id (:comment/id comment)))
      (update r :error (fn [err]
                         (-> err
                             (update :data (fnil assoc {})
                                     :comment/id (:comment/id comment))))))))

;------------------------------------------------------------------------------ Layer 3
;; Git + reply-resolve orchestration

(defn- git-sh
  "Run `git -C worktree-path <args>`. Returns DAG result."
  [worktree-path & args]
  (try
    (let [r (apply process/shell
                   {:dir (str worktree-path)
                    :out :string :err :string :continue true}
                   "git" "-C" (str worktree-path) args)]
      (if (zero? (:exit r))
        (dag/ok {:output (str/trim (:out r ""))})
        (dag/err :policy-eval/git-failed
                 (str/trim (:err r ""))
                 {:exit-code (:exit r) :args (vec args)})))
    (catch Throwable e
      (dag/err :policy-eval/git-failed (.getMessage e) {:args (vec args)}))))

(defn- stage-paths!
  "git add each unique path in `applied-fixes`."
  [worktree-path applied-fixes]
  (let [paths (distinct (map :path applied-fixes))]
    (reduce
     (fn [acc path]
       (let [r (git-sh worktree-path "add" "--" path)]
         (if (dag/ok? r) acc (reduced r))))
     (dag/ok {:staged-count (count paths)})
     paths)))

(defn- commit-message
  "Build a commit message summarizing the applied fixes."
  [applied-fixes]
  (let [n (count applied-fixes)]
    (str "fix(policy-eval): apply " n " standards-pack auto-fix"
         (when (not= n 1) "es")
         "\n\n"
         (str/join "\n"
                   (for [f applied-fixes]
                     (str "- " (:path f) ":" (:line f))))
         "\n\n"
         "Applied by miniforge pr-lifecycle/policy-eval-responder per N13 §2.5.")))

(defn commit-and-push!
  "Stage all touched files, commit, push. Returns DAG result with
   `{:commit-sha :pushed?}` on success."
  [worktree-path applied-fixes]
  (let [stage-r (stage-paths! worktree-path applied-fixes)]
    (if-not (dag/ok? stage-r)
      stage-r
      (let [commit-r (git-sh worktree-path "commit" "-m" (commit-message applied-fixes))]
        (if-not (dag/ok? commit-r)
          commit-r
          (let [sha-r (git-sh worktree-path "rev-parse" "HEAD")]
            (if-not (dag/ok? sha-r)
              sha-r
              (let [push-r (git-sh worktree-path "push")]
                (if-not (dag/ok? push-r)
                  push-r
                  (dag/ok {:commit-sha (:output (:data sha-r))
                           :pushed?    true}))))))))))

(defn- short-sha
  [sha]
  (subs sha 0 (min 10 (count sha))))

(defn- fix-reply-message
  [commit-sha fix]
  (str "Auto-applied in `" (short-sha commit-sha) "`:\n\n"
       "```diff\n"
       "- " (:before fix) "\n"
       "+ " (:after fix) "\n"
       "```\n\n"
       "<sub>Posted by `policy-eval-responder` per N13 §2.5</sub>"))

(defn reply-and-resolve-one!
  "Post the reply on `comment-id`, look up the thread id, resolve it.
   Returns DAG result with `{:reply-posted :resolved :thread-id?}`.
   Resolution is best-effort — if the thread lookup or resolve call
   fails, we still surface the successful reply."
  [worktree-path pr-number commit-sha fix]
  (let [cid (:comment/id fix)
        msg (fix-reply-message commit-sha fix)
        reply-r (github/reply-to-comment worktree-path pr-number cid msg)]
    (if-not (dag/ok? reply-r)
      reply-r
      (let [thread-r (github/get-thread-id worktree-path pr-number cid)]
        (if-not (dag/ok? thread-r)
          (dag/ok {:reply-posted true :resolved false :thread-error (:error thread-r)})
          (let [tid (-> thread-r :data :thread-id)
                resolve-r (github/resolve-conversation worktree-path tid)]
            (dag/ok {:reply-posted true
                     :resolved (dag/ok? resolve-r)
                     :thread-id tid
                     :reply-url (-> reply-r :data :url)})))))))

(defn reply-and-resolve-fixed!
  "For each applied fix, post a reply on its comment thread + resolve.
   Returns a vector of per-fix DAG results."
  [worktree-path pr-number commit-sha applied-fixes]
  (mapv (fn [fix] (reply-and-resolve-one! worktree-path pr-number commit-sha fix))
        applied-fixes))

(defn respond-to-policy-comments!
  "Top-level entry point.

   Steps:
   1. Filter `comments` to policy-eval comments only.
   2. `plan-fixes` to decide :apply / :escalate / :skip per comment.
   3. Materialize each :apply via `materialize-fix!`.
   4. Stage / commit / push the applied fixes.
   5. Reply + resolve on each successfully-applied comment thread.

   Returns DAG result with summary:
     {:applied         [<materialize-result>...]
      :failed-to-apply [<dag-err>...]
      :escalated       [<plan-entry>...]
      :skipped         [<plan-entry>...]
      :commit-sha      <sha>?
      :pushed?         <bool>?
      :replies         [<dag-result>...]?}"
  [worktree-path pr-number comments]
  (let [policy-comments (filterv policy-eval-comment? comments)
        plan            (plan-fixes policy-comments)
        results         (mapv #(materialize-fix! worktree-path %) (:to-apply plan))
        applied-ok      (filter dag/ok? results)
        applied         (mapv :data applied-ok)
        failed-to-apply (mapv :error (filter (complement dag/ok?) results))]
    (cond
      (empty? applied)
      (dag/ok {:applied         []
               :failed-to-apply failed-to-apply
               :escalated       (:to-escalate plan)
               :skipped         (:to-skip plan)
               :commit-sha      nil
               :pushed?         false
               :replies         []})

      :else
      (let [push-r (commit-and-push! worktree-path applied)]
        (if-not (dag/ok? push-r)
          push-r
          (let [{:keys [commit-sha pushed?]} (:data push-r)
                replies (reply-and-resolve-fixed! worktree-path pr-number commit-sha applied)]
            (dag/ok {:applied         applied
                     :failed-to-apply failed-to-apply
                     :escalated       (:to-escalate plan)
                     :skipped         (:to-skip plan)
                     :commit-sha      commit-sha
                     :pushed?         pushed?
                     :replies         replies})))))))
