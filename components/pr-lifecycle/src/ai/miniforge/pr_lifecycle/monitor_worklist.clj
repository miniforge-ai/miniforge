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

(ns ai.miniforge.pr-lifecycle.monitor-worklist
  "Persisted PR monitor work-list — schema, path helpers, and disk I/O.

   Layer 0: WorklistEntry Malli schema and named storage constants.
   Layer 1: Pure helpers — repo-key derivation, worklist-path computation.
   Layer 2: Side-effecting persist!/load/prune functions.

   The work-list tracks which PRs a monitor instance is watching.
   It is keyed by a hash of the git remote origin URL so multiple repos
   coexist under the same monitor directory without collision.

   Storage layout: <home-dir>/pr-monitor/<repo-key>.edn

   Operational parameters (poll-interval, abandon-after-hours) travel
   inside the persisted PR entry map — no operational literal is
   hard-coded here (rule 8).

   Boundary contract (rules 3, 4):
   - persist!/load  → schema/success or schema/failure; never throw.
   - prune-closed-prs → WorklistEntry (pruned or original) or anomaly map."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.edn :as edn]
   [malli.core :as m]
   [malli.error :as me]
   [slingshot.slingshot :refer [try+]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.schema.interface :as schema])
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants and Malli schema

(def ^:private pr-monitor-dir-name
  "Subdirectory name under home-dir for all work-list files."
  "pr-monitor")

(def ^:private sha-algorithm
  "Hash algorithm for repo-key derivation."
  "SHA-256")

(def ^:private repo-key-prefix-length
  "Number of hex characters taken from the SHA-256 digest as the repo-key."
  12)

(def ^:private open-pr-state
  "GitHub PR state string indicating the PR is still open."
  "OPEN")

(def ^:private gh-state-pattern
  "Regex to extract the state field from `gh pr view --json state` output."
  #"\"state\":\"([^\"]+)\"")

(def WorklistPrEntry
  "Malli schema for a single PR entry inside a WorklistEntry.

   Operational parameters (:pr/poll-interval, :pr/abandon-after-hours)
   travel with the entry — no operational literal is hard-coded in this
   namespace (rule 8)."
  [:map
   [:pr/url :string]
   [:pr/number :int]
   [:pr/repo :string]
   [:pr/added-at inst?]
   [:pr/poll-interval {:optional true} :int]
   [:pr/abandon-after-hours {:optional true} :int]])

(def WorklistEntry
  "Malli schema for the persisted work-list EDN file.

   - :worklist/repo-key   — 12-hex-char prefix of SHA-256(remote-origin-url)
   - :worklist/prs        — vector of WorklistPrEntry maps
   - :worklist/updated-at — last-write instant"
  [:map
   [:worklist/repo-key :string]
   [:worklist/prs [:vector WorklistPrEntry]]
   [:worklist/updated-at inst?]])

;------------------------------------------------------------------------------ Layer 1
;; Pure helpers

(defn- sha256-hex
  "Return the full lowercase hex SHA-256 digest of `s`."
  [s]
  (let [md    (MessageDigest/getInstance sha-algorithm)
        bytes (.digest md (.getBytes ^String s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn repo-key
  "Derive a stable, filesystem-safe key from `remote-url` (typically the
   value of `git remote get-url origin`).

   Returns the first `repo-key-prefix-length` characters of the lowercase
   hex SHA-256 digest — long enough to be collision-resistant across the
   repos a single monitor instance will track."
  [remote-url]
  (subs (sha256-hex remote-url) 0 repo-key-prefix-length))

(defn worklist-path
  "Compute the absolute path for the work-list EDN file.

   - `home-dir` — miniforge home directory (from app-config/home-dir)
   - `rkey`     — repo key returned by `repo-key`

   Returns: <home-dir>/pr-monitor/<rkey>.edn"
  [home-dir rkey]
  (str home-dir "/" pr-monitor-dir-name "/" rkey ".edn"))

;------------------------------------------------------------------------------ Layer 2
;; Internal validation helper

(defn- validate-entry
  "Return `entry` when it satisfies WorklistEntry, or an :invalid-input anomaly."
  [entry]
  (if (m/validate WorklistEntry entry)
    entry
    (anomaly/validation-anomaly
     "WorklistEntry schema validation failed"
     :WorklistEntry
     entry
     (me/humanize (m/explain WorklistEntry entry)))))

;------------------------------------------------------------------------------ Layer 2
;; Disk I/O

(defn persist-worklist!
  "Validate `entry` against WorklistEntry and write it as EDN to `path`.
   Creates parent directories as needed.

   Returns:
   - `(schema/success :worklist entry)` on success.
   - `(schema/failure :worklist <msg>)` on validation or I/O failure."
  [path entry]
  (let [validated (validate-entry entry)]
    (if (anomaly/anomaly? validated)
      (schema/failure :worklist
                      (str "Invalid WorklistEntry: "
                           (get-in validated [:anomaly/data :errors])))
      (try+
       (fs/create-dirs (fs/parent path))
       (spit path (pr-str entry))
       (schema/success :worklist entry)
       (catch Object ex
         (schema/failure :worklist
                         (str "Failed to write worklist to " path ": "
                              (ex-message ex))))))))

(defn load-worklist
  "Read and validate a WorklistEntry EDN from `path`.

   Returns:
   - `(schema/success :worklist entry)` on success.
   - `(schema/failure :worklist <msg>)` when path is missing, unreadable,
     or content does not satisfy WorklistEntry."
  [path]
  (cond
    (not (fs/exists? path))
    (schema/failure :worklist (str "Worklist not found: " path))

    :else
    (try+
     (let [entry (edn/read-string (slurp path))
           validated (validate-entry entry)]
       (if (anomaly/anomaly? validated)
         (schema/failure :worklist
                         (str "Corrupt worklist at " path ": "
                              (get-in validated [:anomaly/data :errors])))
         (schema/success :worklist entry)))
     (catch Object ex
       (schema/failure :worklist
                       (str "Failed to read worklist at " path ": "
                            (ex-message ex)))))))

(defn- fetch-pr-state
  "Shell `gh pr view <number> --repo <repo> --json state` and return the
   state string (e.g. \"OPEN\", \"MERGED\", \"CLOSED\"), or an anomaly
   on process failure or missing output field.

   Uses `:throw false` so the process exit code is checked explicitly
   rather than raising a Java exception for non-zero exits."
  [{:pr/keys [number repo]}]
  (try+
   (let [proc @(process/process ["gh" "pr" "view" (str number)
                                 "--repo" repo "--json" "state"]
                                {:out :string :err :string :throw false})]
     (if (zero? (:exit proc))
       (or (second (re-find gh-state-pattern (:out proc)))
           (anomaly/anomaly :fault
                            "gh pr view returned no state field"
                            {:pr/number number :pr/repo repo
                             :out (:out proc)}))
       (anomaly/anomaly :fault
                        "gh pr view exited non-zero"
                        {:pr/number number :pr/repo repo
                         :exit (:exit proc) :err (:err proc)})))
   (catch Object ex
     (anomaly/anomaly :fault
                      "gh pr view process failed to start"
                      {:pr/number number :pr/repo repo
                       :anomaly/ex-message (ex-message ex)}))))

(defn prune-closed-prs
  "Remove merged or closed PR entries from `entry` by querying GitHub.

   For each PR in `:worklist/prs`, shells `gh pr view <number> --repo
   <repo> --json state`. Drops entries whose state is not \"OPEN\".

   Returns:
   - The pruned WorklistEntry when all `gh` calls succeed (may be the
     original map when every PR is still open).
   - An anomaly map if any `gh` invocation fails — safer to leave the
     list unchanged than to silently drop PRs we could not check."
  [entry]
  (let [prs (:worklist/prs entry)]
    (loop [remaining prs
           kept      []]
      (if (empty? remaining)
        (if (= (count kept) (count prs))
          entry
          (assoc entry :worklist/prs kept))
        (let [pr-entry (first remaining)
              state    (fetch-pr-state pr-entry)]
          (if (anomaly/anomaly? state)
            (anomaly/anomaly :fault
                             "gh pr view failed during prune — worklist unchanged"
                             {:failed-pr pr-entry :cause state})
            (recur (rest remaining)
                   (if (= state open-pr-state)
                     (conj kept pr-entry)
                     kept))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Derive repo key from a remote URL
  (repo-key "https://github.com/miniforge-ai/miniforge.git")
  ;; => "3f8a1c2b0e94" (first 12 hex chars of SHA-256)

  ;; Compute work-list path
  (worklist-path "/Users/chris/.miniforge"
                 (repo-key "https://github.com/miniforge-ai/miniforge.git"))
  ;; => "/Users/chris/.miniforge/pr-monitor/3f8a1c2b0e94.edn"

  ;; Construct and persist a worklist
  (let [entry {:worklist/repo-key    "3f8a1c2b0e94"
               :worklist/prs         [{:pr/url              "https://github.com/org/repo/pull/42"
                                       :pr/number           42
                                       :pr/repo             "org/repo"
                                       :pr/added-at         (java.util.Date.)
                                       :pr/poll-interval    60
                                       :pr/abandon-after-hours 72}]
               :worklist/updated-at  (java.util.Date.)}
        path  "/tmp/test-worklist.edn"]
    (persist-worklist! path entry))
  ;; => {:success? true :worklist {...}}

  ;; Load it back
  (load-worklist "/tmp/test-worklist.edn")
  ;; => {:success? true :worklist {...}}

  ;; Missing file
  (load-worklist "/tmp/no-such-file.edn")
  ;; => {:success? false :worklist nil :error "Worklist not found: ..."}

  ;; Prune closed PRs (requires gh auth)
  ;; (prune-closed-prs entry)
  ;; => updated-entry or anomaly

  :leave-this-here)
