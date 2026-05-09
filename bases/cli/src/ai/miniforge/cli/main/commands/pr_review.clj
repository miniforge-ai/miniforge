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

(ns ai.miniforge.cli.main.commands.pr-review
  "PR Review implementation — N13 §2.2 Standards Reviewer entry point.

   Runs compliance-scanner in PR-scoped read-only mode, classifies
   violations, and emits comment records (the renderer shape from
   `compliance-scanner.comments`).

   By default this namespace only renders to stdout — the operator
   can pipe / inspect / forward the output. When invoked with the
   `--post` flag (URL flow) or `:post? true` programmatically, it
   additionally batch-posts the comments as a single PR review via
   `pr-lifecycle/post-review!` (N13 §2.2 Standards Reviewer end-cap).
   Posting requires a PR number and the PR head SHA (`commit-id`);
   the URL flow derives both automatically.

   This namespace does NOT apply fixes — that remains the Comment
   Response Agent's job per the pr-monitoring-workflow informative
   spec.

   The programmatic entry point is `run-pr-review!`, called from
   `commands.pr`'s `pr-review-cmd`."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.compliance-scanner.interface :as scanner]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

(def ^:private default-standards-path ".standards")

;; ── Table layout for emit-table ──────────────────────────────────────
;; Column widths (chars). Tuned for 100-col terminals: file paths get
;; the bulk, line is 6 (covers up to a million-line file), severity is
;; 12 (longest keyword name like ":warning" plus padding), rule trails
;; with no fixed width.
(def ^:private file-col-width      60)
(def ^:private line-col-width      6)
(def ^:private severity-col-width  12)

(def ^:private table-header-fmt
  (str "%-" file-col-width "s %-" line-col-width "s %-" severity-col-width "s %s%n"))

(def ^:private table-row-fmt
  (str "%-" file-col-width "s %-" line-col-width "d %-" severity-col-width "s %s%n"))

(defn- separator-row
  "Build the dashed underline row used between header and body."
  []
  (let [dashes (fn [n] (apply str (repeat n "-")))]
    (str (dashes file-col-width) " "
         (dashes line-col-width) " "
         (dashes severity-col-width) " ----")))

(defn- resolve-pack
  "Resolve a pack by name or path; returns the loaded pack map or nil."
  [pack-ref]
  (cond
    (nil? pack-ref) nil

    (fs/exists? pack-ref)
    (edn/read-string (slurp (str pack-ref)))

    :else
    (let [resource-path (str "policy_pack/packs/" pack-ref ".pack.edn")]
      (when-let [url (io/resource resource-path)]
        (edn/read-string (slurp url))))))

(defn- pack-info
  "Best-effort pack-info extraction for the rendered comment payload.
   Falls back to {:pack/id <pack-ref-or-default> :pack/version \"0.0.0\"}
   when the pack does not declare an id/version."
  [pack pack-ref]
  (cond
    (and (map? pack)
         (or (:pack/id pack) (:pack/version pack)))
    {:pack/id      (or (:pack/id pack)
                       (when pack-ref (str pack-ref))
                       "miniforge-standards")
     :pack/version (or (:pack/version pack) "0.0.0")}

    pack-ref
    {:pack/id (str pack-ref) :pack/version "0.0.0"}

    :else
    {:pack/id "miniforge-standards" :pack/version "0.0.0"}))

(defn- print-summary
  "Print a one-line summary of the review."
  [{:pr-review/keys [summary]}]
  (display/print-info
   (messages/t :pr/review-summary
               {:total        (:total summary)
                :auto-fixable (:auto-fixable summary)
                :needs-review (:needs-review summary)
                :files        (:files-affected summary)
                :rules        (:rules-violated summary)})))

(defn- emit-table
  "Print a human-readable table of comments."
  [comments]
  (when (seq comments)
    (println)
    (printf table-header-fmt "FILE" "LINE" "SEVERITY" "RULE")
    (println (separator-row))
    (doseq [c comments]
      (printf table-row-fmt
              (:comment/path c)
              (:comment/line c)
              (name (or (get-in c [:comment/payload :violation/severity]) :info))
              (str (get-in c [:comment/payload :violation/rule-id]))))))

(defn- emit-edn
  "Print the comments vector as pretty EDN to stdout."
  [comments]
  (pprint/pprint comments))

(defn- emit-json
  "Print the comments vector as RFC 8259-compliant JSON via cheshire.
   Keywords are stringified; the internal `(str)` call in the key-fn
   handles the leading colon for deterministic output."
  [comments]
  (println (json/generate-string comments {:key-fn name})))

(defn- review-summary-body
  "Build the markdown body for the posted PR review from the
   pr-review result's summary."
  [{:pr-review/keys [summary]}]
  (messages/t :pr/review-summary
              {:total        (:total summary)
               :auto-fixable (:auto-fixable summary)
               :needs-review (:needs-review summary)
               :files        (:files-affected summary)
               :rules        (:rules-violated summary)}))

(defn- maybe-post-review!
  "When the operator opted in via `--post`, batch-post the rendered
   comments to the PR via `pr-lifecycle.interface/post-review!`.

   Surfaces three operator-facing outcomes:
   - zero violations  → prints the `:pr/review-post-skipped-empty`
                        info line so the operator sees that the post
                        step ran and intentionally did nothing
   - PR number missing → prints `:pr/review-post-pr-required` error
   - missing commit-id → prints `:pr/review-post-commit-id-required` error
   - otherwise        → posts via `post-review!` and prints either
                        `:pr/review-posted` (with comment count + URL)
                        or `:pr/review-post-failed` (typed code + msg)."
  [repo-path pr-number commit-id result]
  (let [comments (:pr-review/comments result)]
    (cond
      (not (pos? (count comments)))
      (display/print-info (messages/t :pr/review-post-skipped-empty))

      (not (pos-int? pr-number))
      (display/print-error
       (messages/t :pr/review-post-pr-required))

      (or (nil? commit-id) (and (string? commit-id) (str/blank? commit-id)))
      (display/print-error
       (messages/t :pr/review-post-commit-id-required))

      :else
      (let [body (review-summary-body result)
            r    (pr-lifecycle/post-review! repo-path pr-number commit-id body comments)]
        (if (dag/ok? r)
          (display/print-info
           (messages/t :pr/review-posted
                       {:count (:comment-count (:data r))
                        :url   (:url (:data r))}))
          (display/print-error
           (messages/t :pr/review-post-failed
                       {:code    (str (get-in r [:error :code]))
                        :message (get-in r [:error :message])})))))))

(defn run-pr-review!
  "Run a PR-scoped standards review against an existing repo checkout.

   Arguments:
   - repo-path  - string path to a repo/worktree checked out at the PR's
                  head SHA
   - opts       - map with:
       :base-ref   - REQUIRED. Git ref of the PR's base branch
                     (e.g., \"origin/main\"). Passed to compliance-scanner
                     as :since.
       :standards  - path to .standards dir (default \".standards\")
       :pack       - pack name or path (optional)
       :rules      - rule selector string or keyword (default :all)
       :out        - :table | :edn | :json (default :table)
       :post?      - when truthy, batch-post the rendered comments to
                     the PR as a single review (event=COMMENT). Requires
                     :pr-number AND :commit-id.
       :pr-number  - integer PR number, required when :post? is truthy.
       :commit-id  - full SHA of the PR's head commit. Required when
                     :post? is truthy. Falls back to
                     `(pr-lifecycle/git-head-sha repo-path)` when
                     omitted, on the assumption the PR is checked out.

   Side effects:
   - Prints summary + comments to stdout in the requested format.
   - When :post? is set: posts a single review batching all comments
     via `pr-lifecycle.interface/post-review!`.

   Returns the pr-review result map (per scanner/pr-review)."
  [repo-path
   {:keys [base-ref standards pack rules out post? pr-number commit-id]
    :or   {standards default-standards-path
           rules     :all
           out       :table}}]
  (let [pack-ref pack
        pack-map (resolve-pack pack-ref)
        rules-kw (cond
                   (keyword? rules) rules
                   (string? rules)  (keyword rules)
                   :else            :all)
        result   (scanner/pr-review
                  repo-path standards
                  (cond-> {:base-ref  base-ref
                           :rules     rules-kw
                           :pack-info (pack-info pack-map pack-ref)}
                    pack-map (assoc :pack pack-map)))]
    (print-summary result)
    (case out
      :edn   (emit-edn (:pr-review/comments result))
      :json  (emit-json (:pr-review/comments result))
      :table (emit-table (:pr-review/comments result))
      (display/print-error
       (messages/t :pr/review-unknown-out {:fmt (name out)})))
    (when post?
      (maybe-post-review! repo-path
                          pr-number
                          (or commit-id (pr-lifecycle/git-head-sha repo-path))
                          result))
    result))

(defn run-pr-review-by-path-cmd
  "CLI entry point for `miniforge pr review --repo <path> --base <ref>`.

   For when the operator already has a checkout — useful in dogfood and
   from janitors that operate on existing worktrees.

   Flags: required `--base <git-ref>`; optional `--repo <path>`
   (defaults to `(fs/cwd)`), `--standards <path>`, `--pack <name|path>`,
   `--rules <selector>`, `--out edn|json|table`. The CLI surface in
   `commands.pr` registers `[:url]` as the only positional via
   `:args->opts`, so the repo path comes from the `--repo` flag, not
   from a positional argument."
  [opts]
  (let [repo-path (get opts :repo (str (fs/cwd)))
        base-ref  (get opts :base)]
    (cond
      (not (fs/exists? repo-path))
      (display/print-error
       (messages/t :pr/review-repo-not-found {:path repo-path}))

      (or (nil? base-ref) (= "" base-ref))
      (display/print-error
       (messages/t :pr/review-base-required))

      :else
      (try
        (run-pr-review! repo-path
                        (cond-> {:base-ref base-ref}
                          (:standards opts) (assoc :standards (:standards opts))
                          (:pack opts)      (assoc :pack (:pack opts))
                          (:rules opts)     (assoc :rules (:rules opts))
                          (:out opts)       (assoc :out (keyword (:out opts)))
                          (:post opts)      (assoc :post? true)
                          (:pr-number opts) (assoc :pr-number
                                                   (cond
                                                     (integer? (:pr-number opts)) (:pr-number opts)
                                                     :else (try (Long/parseLong (str (:pr-number opts)))
                                                                (catch Exception _ nil))))
                          (:commit-id opts) (assoc :commit-id (:commit-id opts))))
        (catch Exception e
          (display/print-error
           (messages/t :pr/review-failed {:message (ex-message e)})))))))
