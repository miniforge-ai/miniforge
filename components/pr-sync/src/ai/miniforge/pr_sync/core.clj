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

(ns ai.miniforge.pr-sync.core
  "PR fleet synchronization: fleet config I/O and provider CLI interaction.

   Shared brick providing PR discovery and fleet repository management.
   Both the web-dashboard and TUI depend on this component.

   Layer 0: Constants and pure helpers
   Layer 1: Fleet config I/O
   Layer 2: Provider CLI interaction (I/O)

   Pure status mapping lives in ai.miniforge.pr-sync.status."
  (:require
   [ai.miniforge.pr-sync.status :as status]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def default-fleet-config-path
  (str (System/getProperty "user.home") "/.miniforge/config.edn"))

(def github-repo-slug-pattern
  #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

(def gitlab-repo-slug-pattern
  #"^gitlab:[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+$")

;------------------------------------------------------------------------------ Layer 0b
;; Pure helpers

(defn normalize-repo-slug
  [repo]
  (-> (str repo)
      str/trim
      str/lower-case))

(defn valid-repo-slug?
  [repo]
  (boolean
   (or (re-matches github-repo-slug-pattern repo)
       (re-matches gitlab-repo-slug-pattern repo))))

(defn repo-provider
  [repo]
  (if (str/starts-with? (or repo "") "gitlab:")
    :gitlab
    :github))

(defn provider-repo-slug
  "Strip provider prefix for downstream CLI calls."
  [repo]
  (if (= :gitlab (repo-provider repo))
    (subs repo (count "gitlab:"))
    repo))

(defn url-encode
  [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

(defn result-success
  [data]
  (merge {:success? true} data))

(defn result-failure
  ([message]
   (result-failure message nil))
  ([message data]
   (merge {:success? false :error (or (some-> message str not-empty)
                                      "Operation failed with no additional error details.")}
          data)))

(defn gh-error-message
  [out err]
  (let [msg (str/trim (or (if (str/blank? err) out err) ""))]
    (when-not (str/blank? msg) msg)))

(defn ex-msg
  [^Exception e]
  (or (.getMessage e)
      (some-> e class .getName)
      "unknown exception"))

(defn normalized-limit
  [limit default]
  (let [n (if (integer? limit) limit default)]
    (if (pos? n) n default)))

(defn normalized-repos
  "Extract, normalize, and validate repo slugs from a fleet config map."
  [cfg]
  (->> (get-in cfg [:fleet :repos] [])
       (map normalize-repo-slug)
       (filter valid-repo-slug?)
       vec))

;------------------------------------------------------------------------------ Layer 1
;; Fleet config I/O

(defn load-fleet-config
  "Load fleet configuration from ~/.miniforge/config.edn.
   Returns {:fleet {:repos [...]}} or default empty config."
  ([] (load-fleet-config default-fleet-config-path))
  ([path]
   (try
     (if (.exists (io/file path))
       (let [config (edn/read-string (slurp path))]
         (if (map? config)
           config
           {:fleet {:repos []}}))
       {:fleet {:repos []}})
     (catch Exception _
       {:fleet {:repos []}}))))

(defn save-fleet-config!
  "Write fleet configuration to disk."
  ([config] (save-fleet-config! config default-fleet-config-path))
  ([config path]
   (let [file (io/file path)]
     (.mkdirs (.getParentFile file))
     (spit file (pr-str config)))))

(defn get-configured-repos
  "Get configured fleet repositories as normalized slugs."
  ([] (get-configured-repos default-fleet-config-path))
  ([path]
   (-> (load-fleet-config path) normalized-repos distinct vec)))

(defn add-repo!
  "Add a repository slug to fleet configuration.
   Accepted formats:
   - GitHub: owner/name
   - GitLab: gitlab:group/name (subgroups supported)
   Returns {:success? bool :added? bool :repo str :repos [...]}."
  ([repo-slug] (add-repo! repo-slug default-fleet-config-path))
  ([repo-slug path]
   (let [repo (normalize-repo-slug repo-slug)]
     (cond
       (str/blank? repo)
       (result-failure "Repository is required. Use owner/name or gitlab:group/name." {:repo repo})

       (not (valid-repo-slug? repo))
       (result-failure "Invalid repository format. Expected owner/name or gitlab:group/name (not a filesystem path or URL)." {:repo repo})

       :else
       (let [cfg (load-fleet-config path)
             repos (normalized-repos cfg)
             exists? (some #{repo} repos)
             next-repos (if exists? repos (conj repos repo))
             next-cfg (assoc-in cfg [:fleet :repos] (vec (distinct next-repos)))]
         (save-fleet-config! next-cfg path)
         (result-success {:added? (not (boolean exists?))
                          :repo repo
                          :repos (get-in next-cfg [:fleet :repos])}))))))

(defn remove-repo!
  "Remove a repository slug from fleet configuration.
   Returns {:success? bool :removed? bool :repo str :repos [...]}."
  ([repo-slug] (remove-repo! repo-slug default-fleet-config-path))
  ([repo-slug path]
   (let [repo (normalize-repo-slug repo-slug)]
     (if (str/blank? repo)
       (result-failure "Repository is required." {:repo repo})
       (let [cfg (load-fleet-config path)
             repos (normalized-repos cfg)
             existed? (some #{repo} repos)
             next-repos (vec (remove #{repo} repos))
             next-cfg (assoc-in cfg [:fleet :repos] next-repos)]
         (save-fleet-config! next-cfg path)
         (result-success {:removed? (boolean existed?)
                          :repo repo
                          :repos next-repos}))))))

;------------------------------------------------------------------------------ Layer 2
;; Provider CLI interaction (I/O)

(defn run-gh
  "Execute a `gh` CLI command. Returns {:success? bool :out str :err str}."
  [& args]
  (try
    (let [{:keys [exit out err]} (apply shell/sh "gh" args)]
      {:success? (zero? exit)
       :out (or out "")
       :err (or err "")})
    (catch Exception e
      {:success? false
       :out ""
       :err (.getMessage e)})))

(declare run-glab)

(defn fetch-github-prs
  "Fetch GitHub PRs by state (:open, :closed, :merged, :all)."
  [repo state]
  (let [repo*  (provider-repo-slug repo)
        gh-state (case state
                   :merged "merged" :closed "closed" :all "all" "open")
        {:keys [success? out err]} (run-gh "pr" "list"
                                           "--repo" repo*
                                           "--state" gh-state
                                           "--json" "number,title,url,state,mergedAt,headRefName,isDraft,reviewDecision,statusCheckRollup,mergeStateStatus,additions,deletions,changedFiles,author")]
    (if-not success?
      (result-failure (gh-error-message out err) {:repo repo :provider :github})
      (try
        (let [rows (json/parse-string out true)]
          (result-success
           {:repo repo
            :provider :github
            :prs (->> rows
                      (map #(status/provider-pr->train-pr % repo))
                      (sort-by :pr/number)
                      vec)}))
        (catch Exception e
          (result-failure (str "Failed to parse PR list for " repo ".")
                          {:repo repo :provider :github :parse-error (.getMessage e)}))))))

(defn fetch-open-github-prs
  [repo]
  (let [result (fetch-github-prs repo :open)]
    (if (succeeded? result)
      (update result :prs (fn [prs] (vec (remove #(#{:closed :merged} (:pr/status %)) prs))))
      result)))

(defn gitlab-state-param
  "Map canonical state keyword to GitLab API state parameter."
  [state]
  (case state
    :open   "opened"
    :closed "closed"
    :merged "merged"
    :all    "all"
    "opened"))

(defn- mr-graphql-fragment
  "Build a GraphQL fragment for a single MR iid."
  [iid]
  (str "mr" iid ": mergeRequest(iid: \"" iid "\") "
       "{ diffStatsSummary { additions deletions fileCount } }"))

(defn- extract-mr-diff-stats
  "Extract diff stats for an iid from a parsed GraphQL project map.
   Returns [iid stats-map] or nil when stats are absent."
  [project iid]
  (let [k (keyword (str "mr" iid))
        stats (get-in project [k :diffStatsSummary])]
    (when stats
      [iid {:additions  (:additions stats 0)
            :deletions  (:deletions stats 0)
            :file-count (:fileCount stats 0)}])))

(defn fetch-gitlab-diff-stats-batch
  "Fetch diff stats for multiple MRs in a single GraphQL query.
   Returns {iid {:additions N :deletions N :file-count N}} or empty map on failure."
  [repo* iids]
  (when (seq iids)
    (try
      (let [;; Build aliased GraphQL query: mr26: mergeRequest(iid: \"26\") { ... }
            mr-fragments (str/join " " (map mr-graphql-fragment iids))
            query (str "{ project(fullPath: \"" repo* "\") { " mr-fragments " } }")
            {:keys [success? out]} (run-glab "api" "graphql" "-f" (str "query=" query))]
        (if-not success?
          {}
          (let [data (json/parse-string out true)
                project (get-in data [:data :project])]
            (into {}
              (keep (partial extract-mr-diff-stats project))
              iids))))
      (catch Exception _ {}))))

(defn enrich-gitlab-mrs-with-diff-stats
  "Enrich GitLab MR maps with diff stats via a single batched GraphQL query.
   Only enriches open/draft MRs (skip closed/merged)."
  [repo* mrs]
  (let [open-mrs (filter #(= "opened" (some-> (:state %) str str/lower-case)) mrs)
        iids (mapv :iid open-mrs)]
    (if (empty? iids)
      mrs
      (let [stats (fetch-gitlab-diff-stats-batch repo* iids)]
        (mapv (fn [mr]
                (if-let [s (get stats (:iid mr))]
                  (assoc mr
                         :additions (:additions s 0)
                         :deletions (:deletions s 0)
                         :changes_count (or (:file-count s) (:changes_count mr)))
                  mr))
              mrs)))))

(defn fetch-gitlab-mrs-by-state
  "Fetch GitLab merge requests by state (:open, :closed, :merged, :all).
   Enriches open MRs with diff stats (additions/deletions) via per-MR API calls."
  [repo state]
  (let [repo*    (provider-repo-slug repo)
        gl-state (gitlab-state-param state)
        endpoint (str "projects/" (url-encode repo*)
                      "/merge_requests?state=" gl-state
                      "&per_page=100&with_merge_status_recheck=true")
        {:keys [success? out err]} (run-glab "api" endpoint)]
    (if-not success?
      (result-failure (gh-error-message out err) {:repo repo :provider :gitlab})
      (try
        (let [rows (json/parse-string out true)
              ;; Enrich with diff stats for open MRs
              enriched (if (#{:open :all} state)
                         (enrich-gitlab-mrs-with-diff-stats repo* rows)
                         rows)
              prs (->> enriched
                       (map #(status/gitlab-mr->train-pr % repo))
                       (sort-by :pr/number)
                       vec)
              ;; GitLab API sometimes returns MRs outside the requested state.
              ;; Post-filter to ensure we only return what was asked for.
              expected-statuses (case state
                                 :open   #{:open :draft :merge-ready :changes-requested}
                                 :closed #{:closed}
                                 :merged #{:merged}
                                 :all    nil)
              filtered (if expected-statuses
                         (filterv #(contains? expected-statuses (:pr/status %)) prs)
                         prs)]
          (result-success
           {:repo repo
            :provider :gitlab
            :prs filtered}))
        (catch Exception e
          (result-failure (str "Failed to parse merge request list for " repo ".")
                          {:repo repo :provider :gitlab :parse-error (.getMessage e)}))))))

(defn fetch-open-gitlab-mrs
  [repo]
  (fetch-gitlab-mrs-by-state repo :open))

(defn fetch-open-prs
  "Fetch open PR/MR items for a single configured repository.
   GitHub repos use `gh pr list`; GitLab repos use `glab api`.
   Returns {:success? bool :repo str :prs [TrainPR ...]}."
  [repo]
  (case (repo-provider repo)
    :gitlab (fetch-open-gitlab-mrs repo)
    (fetch-open-github-prs repo)))

(defn fetch-prs-by-state
  "Fetch PRs for a single repo by state (:open, :closed, :merged, :all).
   Returns {:success? bool :repo str :prs [TrainPR ...]}."
  [repo state]
  (case (repo-provider repo)
    :gitlab (fetch-gitlab-mrs-by-state repo state)
    (fetch-github-prs repo state)))

(defn classify-error
  "Classify a provider error message into a category keyword."
  [error-msg]
  (let [msg (str/lower-case (or error-msg ""))]
    (cond
      (or (str/includes? msg "401") (str/includes? msg "403")
          (str/includes? msg "bad credentials") (str/includes? msg "forbidden"))
      :auth-failure

      (or (str/includes? msg "rate limit") (str/includes? msg "rate_limit"))
      :rate-limited

      (or (str/includes? msg "404") (str/includes? msg "not found"))
      :not-found

      (or (str/includes? msg "econnrefused") (str/includes? msg "connection")
          (str/includes? msg "timeout") (str/includes? msg "network"))
      :network-error

      (or (str/includes? msg "parse") (str/includes? msg "json")
          (str/includes? msg "unexpected"))
      :parse-error

      :else :unknown)))

(defn error-hint
  "Return a human-readable hint for a given error category."
  [category]
  (case category
    :auth-failure  "Check your authentication token or repository permissions."
    :rate-limited  "You have been rate-limited. Wait a few minutes and try again."
    :not-found     "Repository not found. Check the slug or your access permissions."
    :network-error "Network error. Check your internet connection."
    :parse-error   "Failed to parse the provider response."
    "An unexpected error occurred."))

(defn classify-sync-error
  "Classify an error message and return a map with :error-category and :hint."
  [error-msg]
  (let [category (classify-error error-msg)]
    {:error-category category
     :hint           (error-hint category)}))

(defn fetch-repo-with-status
  "Fetch open PRs for a single repo and return a status map.
   Returns {:status :ok/:error, :repo str, :prs [...], :pr-count N,
            :error-category kw, :error str, :hint str}."
  [repo]
  (try
    (let [result (fetch-open-prs repo)]
      (if (succeeded? result)
        {:status   :ok
         :repo     repo
         :prs      (vec (:prs result))
         :pr-count (count (:prs result))}
        (let [err-msg (or (:error result)
                          (gh-error-message (:out result) (:err result))
                          "Unknown error")
              category (classify-error err-msg)]
          {:status         :error
           :repo           repo
           :error          err-msg
           :error-category category
           :hint           (error-hint category)})))
    (catch Exception e
      (let [err-msg (ex-msg e)
            category (classify-error err-msg)]
        {:status         :error
         :repo           repo
         :error          err-msg
         :error-category category
         :hint           (error-hint category)}))))

(defn build-sync-summary
  "Build a summary map from a sequence of repo sync statuses."
  [sync-statuses]
  (let [total  (count sync-statuses)
        ok     (count (filter #(= :ok (:status %)) sync-statuses))
        errors (- total ok)]
    {:total    total
     :ok       ok
     :errors   errors
     :all-ok?  (zero? errors)
     :partial? (and (pos? ok) (pos? errors))
     :none-ok? (and (pos? total) (zero? ok))}))

(defn fetch-fleet-with-status
  "Fetch open PRs for all configured fleet repos, returning structured results.
   Returns {:prs [...], :sync-statuses [...], :summary {...}}.

   Options:
   - :config-path  - Override config file path (for testing)
   - :parallel?    - Use parallel fetching (default false)"
  [& [{:keys [config-path parallel?] :or {parallel? false}}]]
  (let [repos (get-configured-repos (or config-path default-fleet-config-path))]
    (if (empty? repos)
      {:prs           []
       :sync-statuses []
       :summary       (build-sync-summary [])}
      (let [map-fn       (if parallel? pmap map)
            statuses     (vec (map-fn fetch-repo-with-status repos))
            all-prs      (->> statuses
                              (filter #(= :ok (:status %)))
                              (mapcat :prs)
                              vec)]
        {:prs           all-prs
         :sync-statuses statuses
         :summary       (build-sync-summary statuses)}))))

(defn fetch-all-fleet-prs
  "Fetch open PRs for all configured fleet repositories.
   Returns flat vector of TrainPR maps with :pr/repo set.

   Options:
   - :config-path - Override config file path (for testing)
   - :state       - :open (default), :closed, :merged, :all"
  [& [{:keys [config-path state] :or {state :open}}]]
  (let [repos (get-configured-repos (or config-path default-fleet-config-path))
        fetch-fn (if (= :open state) fetch-open-prs #(fetch-prs-by-state % state))]
    (if (empty? repos)
      []
      (->> repos
           (pmap fetch-fn)
           (filter succeeded?)
           (mapcat :prs)
           vec))))

(defn discover-repos!
  "Discover repositories from a GitHub org/user and add them to fleet config.

   Options:
   - :owner - org/user owner to scope discovery
   - :limit - max repos to ingest (default 50)
   - :config-path - Override config path (for testing)"
  [{:keys [owner limit config-path]
    :or {limit 50}}]
  (let [path (or config-path default-fleet-config-path)
        limit* (normalized-limit limit 50)
        owner* (some-> owner str/trim not-empty)
        endpoint (if owner*
                   (str "orgs/" owner* "/repos?per_page=100")
                   "user/repos?per_page=100")
        result (run-gh "api" endpoint)]
    (if-not (succeeded? result)
      (result-failure (gh-error-message (:out result) (:err result)))
      (try
        (let [repos (->> (json/parse-string (:out result) true)
                         (keep :full_name)
                         (map normalize-repo-slug)
                         (filter valid-repo-slug?)
                         distinct
                         (take limit*)
                         vec)
              cfg (load-fleet-config path)
              existing (normalized-repos cfg)
              merged (vec (distinct (concat existing repos)))
              added (vec (remove (set existing) merged))
              next-cfg (assoc-in cfg [:fleet :repos] merged)]
          (save-fleet-config! next-cfg path)
          (result-success {:owner owner*
                           :discovered (count repos)
                           :added (count added)
                           :repos merged
                           :added-repos added}))
        (catch Exception e
          (result-failure "Failed to parse repository list."
                          {:error (ex-msg e)}))))))

(def viewer-repos-graphql-query
  "query($perPage:Int!,$after:String){
     viewer {
       repositories(
         first:$perPage,
         after:$after,
         affiliations:[OWNER,COLLABORATOR,ORGANIZATION_MEMBER],
         orderBy:{field:UPDATED_AT,direction:DESC}
       ) {
         nodes { nameWithOwner }
         pageInfo { hasNextPage endCursor }
       }
     }
   }")

(defn run-glab
  "Execute a `glab` CLI command. Returns {:success? bool :out str :err str}."
  [& args]
  (try
    (let [{:keys [exit out err]} (apply shell/sh "glab" args)]
      {:success? (zero? exit)
       :out (or out "")
       :err (or err "")})
    (catch Exception e
      {:success? false
       :out ""
       :err (.getMessage e)})))

(defn parse-gh-full-name-repos
  [out limit]
  (->> (json/parse-string out true)
       (keep :full_name)
       (map normalize-repo-slug)
       (filter valid-repo-slug?)
       distinct
       (take limit)
       vec))

(defn viewer-repos-gh-args
  "Build `gh api graphql` arguments for a page of viewer repositories."
  [limit acc after]
  (let [remaining (- limit (count acc))
        per-page (max 1 (min 100 remaining))]
    (cond-> ["api" "graphql"
             "-f" (str "query=" viewer-repos-graphql-query)
             "-F" (str "perPage=" per-page)]
      after (conj "-F" (str "after=" after)))))

(defn parse-viewer-repos-page
  "Extract normalized repo slugs and pagination info from a GraphQL viewer response."
  [parsed]
  (let [nodes (or (get-in parsed [:data :viewer :repositories :nodes] []) [])
        page-info (or (get-in parsed [:data :viewer :repositories :pageInfo] {}) {})
        repos (->> nodes
                   (keep :nameWithOwner)
                   (map normalize-repo-slug)
                   (filter valid-repo-slug?)
                   distinct
                   vec)]
    {:repos repos
     :has-next? (boolean (:hasNextPage page-info))
     :cursor (:endCursor page-info)}))

(defn list-viewer-repos
  "List repos visible to the authenticated user across affiliations."
  [limit]
  (try
    (loop [after nil
           acc []]
      (let [result (apply run-gh (viewer-repos-gh-args limit acc after))]
        (if-not (succeeded? result)
          (result-failure (gh-error-message (:out result) (:err result)))
          (let [parsed-result (try
                                {:ok (json/parse-string (:out result) true)}
                                (catch Exception e
                                  {:error (.getMessage e)}))]
            (if-let [err (:error parsed-result)]
              (result-failure "Failed to parse repository list."
                              {:error err})
              (let [{:keys [repos has-next? cursor]} (parse-viewer-repos-page (:ok parsed-result))
                    next-acc (->> (concat acc repos) distinct (take limit) vec)]
                (if (and has-next? cursor (< (count next-acc) limit))
                  (recur cursor next-acc)
                  (result-success {:owner nil :provider :github :repos next-acc}))))))))
    (catch Exception e
      (result-failure "Failed to list accessible repositories."
                      {:error (ex-msg e)}))))

(defn list-github-owner-repos
  [owner limit]
  (let [owner* (some-> owner str str/trim not-empty)
        org-endpoint (str "orgs/" owner* "/repos?per_page=100&type=all&sort=updated")
        org-result (run-gh "api" org-endpoint)
        user-endpoint (str "users/" owner* "/repos?per_page=100&type=owner&sort=updated")
        result (if (succeeded? org-result)
                 org-result
                 (run-gh "api" user-endpoint))]
    (if-not (succeeded? result)
      (result-failure (or (gh-error-message (:out result) (:err result))
                          (gh-error-message (:out org-result) (:err org-result)))
                      {:owner owner* :provider :github})
      (try
        (result-success {:owner owner*
                         :provider :github
                         :repos (parse-gh-full-name-repos (:out result) limit)})
        (catch Exception e
          (result-failure "Failed to parse repository list."
                          {:owner owner* :provider :github :error (ex-msg e)}))))))

(defn list-github-viewer-orgs
  []
  (let [result (run-gh "api" "user/orgs?per_page=100")]
    (if-not (succeeded? result)
      (result-failure (gh-error-message (:out result) (:err result))
                      {:provider :github :error-source :orgs})
      (try
        (let [orgs (->> (json/parse-string (:out result) true)
                        (keep :login)
                        (map str/lower-case)
                        distinct
                        vec)]
          (result-success {:provider :github :orgs orgs}))
        (catch Exception e
          (result-failure "Failed to parse organization list."
                          {:provider :github :error-source :orgs :error (ex-msg e)}))))))

(defn list-github-user-repos-fallback
  [limit]
  (let [result (run-gh "api" "user/repos?per_page=100&sort=updated")]
    (if-not (succeeded? result)
      (result-failure (gh-error-message (:out result) (:err result))
                      {:provider :github :error-source :rest})
      (try
        (result-success {:owner nil
                         :provider :github
                         :repos (parse-gh-full-name-repos (:out result) limit)})
        (catch Exception e
          (result-failure "Failed to parse repository list."
                          {:owner nil :provider :github :error-source :rest :error (ex-msg e)}))))))

(defn list-github-accessible-repos
  [limit]
  (let [viewer (list-viewer-repos limit)
        orgs-result (list-github-viewer-orgs)
        org-results (if (succeeded? orgs-result)
                      (->> (:orgs orgs-result)
                           (map #(list-github-owner-repos % limit))
                           doall)
                      [])
        repos (->> (concat (or (:repos viewer) [])
                           (mapcat #(or (:repos %) []) (filter succeeded? org-results)))
                   distinct
                   (take limit)
                   vec)
        warnings (vec (concat
                       (when-not (succeeded? viewer)
                         [(or (:error viewer) "GraphQL browse failed.")])
                       (when-not (succeeded? orgs-result)
                         [(or (:error orgs-result) "Organization browse failed.")])
                       (for [{:keys [success? owner error]} org-results
                             :when (not success?)]
                         (str "Org " owner ": " (or error "browse failed")))))]
    (cond
      (seq repos)
      (cond-> (result-success {:owner nil :provider :github :repos repos})
        (seq warnings) (assoc :warnings warnings))

      ;; GraphQL failed and org listing failed/empty: try REST fallback
      :else
      (let [fallback (list-github-user-repos-fallback limit)]
        (if (succeeded? fallback)
          (cond-> fallback
            (seq warnings) (assoc :warnings warnings))
          (result-failure (or (:error fallback)
                              (:error viewer)
                              (:error orgs-result)
                              "Failed to list accessible repositories.")
                          {:owner nil :provider :github :error-source :rest}))))))

(defn parse-glab-project-repos
  [out limit]
  (->> (json/parse-string out true)
       (keep :path_with_namespace)
       (map (fn [path]
              (str "gitlab:" (normalize-repo-slug path))))
       (filter valid-repo-slug?)
       distinct
       (take limit)
       vec))

(defn list-gitlab-repos
  [{:keys [owner limit]}]
  (let [owner* (some-> owner str str/trim not-empty)
        endpoint (if owner*
                   (str "groups/" (url-encode owner*)
                        "/projects?include_subgroups=true&archived=false&per_page=100&simple=true&order_by=last_activity_at&sort=desc")
                   "projects?membership=true&archived=false&per_page=100&simple=true&order_by=last_activity_at&sort=desc")
        result (run-glab "api" endpoint)]
    (if-not (succeeded? result)
      (result-failure (gh-error-message (:out result) (:err result))
                      {:owner owner* :provider :gitlab})
      (try
        (result-success {:owner owner*
                         :provider :gitlab
                         :repos (parse-glab-project-repos (:out result) limit)})
        (catch Exception e
          (result-failure "Failed to parse GitLab repository list."
                          {:owner owner* :provider :gitlab :error (ex-msg e)}))))))

(defn list-org-repos
  "List repository slugs from configured providers (read-only browse).

   Options:
   - :owner - org/group name (provider-specific)
   - :limit - max repos to return (default 100)
   - :provider - :github, :gitlab, or :all (default :github)

   Returns {:success? bool :repos [\"owner/name\"|\"gitlab:group/name\" ...]}."
  [{:keys [owner limit provider] :or {limit 100 provider :github}}]
  (let [owner* (some-> owner str str/trim not-empty)
        limit* (normalized-limit limit 100)
        provider* (keyword (name (or provider :github)))]
    (case provider*
      :github
      (if owner*
        (list-github-owner-repos owner* limit*)
        (list-github-accessible-repos limit*))

      :gitlab
      (list-gitlab-repos {:owner owner* :limit limit*})

      :all
      (let [gh-result (if owner*
                        (list-github-owner-repos owner* limit*)
                        (list-github-accessible-repos limit*))
            gl-result (list-gitlab-repos {:owner owner* :limit limit*})
            repos (->> (concat (or (:repos gh-result) [])
                               (or (:repos gl-result) []))
                       distinct
                       (take limit*)
                       vec)
            warnings (vec (concat
                           (when-not (succeeded? gh-result)
                             [(str "GitHub: " (or (:error gh-result) "browse failed"))])
                           (when-not (succeeded? gl-result)
                             [(str "GitLab: " (or (:error gl-result) "browse failed"))])
                           (or (:warnings gh-result) [])))]
        (if (seq repos)
          (cond-> (result-success {:owner owner* :provider :all :repos repos})
            (seq warnings) (assoc :warnings warnings))
          (result-failure (or (:error gh-result)
                              (:error gl-result)
                              "Failed to browse repositories.")
                          {:owner owner* :provider :all})))

      (result-failure (str "Unknown provider: " provider*)
                      {:owner owner* :provider provider*}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Config management
  (get-configured-repos)
  (add-repo! "miniforge-ai/miniforge")
  (remove-repo! "miniforge-ai/miniforge")

  ;; Fetch PRs
  (fetch-open-prs "miniforge-ai/miniforge")
  (fetch-all-fleet-prs)

  ;; Status mapping — see ai.miniforge.pr-sync.status

  :leave-this-here)
