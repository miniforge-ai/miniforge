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
   violations, and emits comment records ready to post via a provider
   connector. Does NOT post comments and does NOT apply fixes — that
   is the job of downstream pipeline steps and the Comment Response
   Agent.

   This namespace exposes a programmatic `run-pr-review!` that the
   `pr review <url>` CLI entry point in `commands.pr` delegates to."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.compliance-scanner.interface :as scanner]))

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

   Side effects:
   - Prints summary + comments to stdout in the requested format.

   Returns the pr-review result map (per scanner/pr-review)."
  [repo-path
   {:keys [base-ref standards pack rules out]
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
                          (:out opts)       (assoc :out (keyword (:out opts)))))
        (catch Exception e
          (display/print-error
           (messages/t :pr/review-failed {:message (ex-message e)})))))))
