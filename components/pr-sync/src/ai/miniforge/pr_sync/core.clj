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
  "PR fleet synchronization: GitHub fetch, fleet config, status mapping.

   Shared brick providing PR discovery and fleet repository management.
   Both the web-dashboard and TUI depend on this component.

   Layer 0: Constants and pure helpers
   Layer 1: Fleet config I/O
   Layer 2: GitHub PR status mapping (pure)
   Layer 3: GitHub CLI interaction (I/O)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private default-fleet-config-path
  (str (System/getProperty "user.home") "/.miniforge/config.edn"))

(def ^:private github-repo-slug-pattern
  #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

(def ^:private gitlab-repo-slug-pattern
  #"^gitlab:[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+$")

(def ^:private failed-check-conclusions
  #{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED" "STARTUP_FAILURE"})

(def ^:private passing-check-conclusions
  #{"SUCCESS" "NEUTRAL" "SKIPPED"})

(def ^:private pending-check-states
  #{"PENDING" "QUEUED" "IN_PROGRESS" "WAITING" "REQUESTED"})

;------------------------------------------------------------------------------ Layer 0b
;; Pure helpers

(defn- normalize-repo-slug
  [repo]
  (-> (str repo)
      str/trim
      str/lower-case))

(defn- valid-repo-slug?
  [repo]
  (boolean
   (or (re-matches github-repo-slug-pattern repo)
       (re-matches gitlab-repo-slug-pattern repo))))

(defn- repo-provider
  [repo]
  (if (str/starts-with? (or repo "") "gitlab:")
    :gitlab
    :github))

(defn- provider-repo-slug
  "Strip provider prefix for downstream CLI calls."
  [repo]
  (if (= :gitlab (repo-provider repo))
    (subs repo (count "gitlab:"))
    repo))

(defn- url-encode
  [s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- result-success
  [data]
  (merge {:success? true} data))

(defn- result-failure
  ([message]
   (result-failure message nil))
  ([message data]
   (merge {:success? false :error (or (some-> message str not-empty)
                                      "Operation failed with no additional error details.")}
          data)))

(defn- gh-error-message
  [out err]
  (let [msg (str/trim (or (if (str/blank? err) out err) ""))]
    (when-not (str/blank? msg) msg)))

(defn- ex-msg
  [^Exception e]
  (or (.getMessage e)
      (some-> e class .getName)
      "unknown exception"))

(defn- normalized-limit
  [limit default]
  (let [n (if (integer? limit) limit default)]
    (if (pos? n) n default)))

;------------------------------------------------------------------------------ Layer 1
;; Fleet config I/O

(defn load-fleet-config
  "Load fleet configuration from ~/.miniforge/config.edn.
   Returns {:fleet {:repos [...]}} or default empty config."
  ([] (load-fleet-config default-fleet-config-path))
  ([path]
   (try
     (if (.exists (io/file path))
       (edn/read-string (slurp path))
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
   (->> (get-in (load-fleet-config path) [:fleet :repos] [])
        (map normalize-repo-slug)
        (filter valid-repo-slug?)
        distinct
        vec)))

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
             repos (->> (get-in cfg [:fleet :repos] [])
                        (map normalize-repo-slug)
                        (filter valid-repo-slug?)
                        vec)
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
             repos (->> (get-in cfg [:fleet :repos] [])
                        (map normalize-repo-slug)
                        (filter valid-repo-slug?)
                        vec)
             existed? (some #{repo} repos)
             next-repos (vec (remove #{repo} repos))
             next-cfg (assoc-in cfg [:fleet :repos] next-repos)]
         (save-fleet-config! next-cfg path)
         (result-success {:removed? (boolean existed?)
                          :repo repo
                          :repos next-repos}))))))

;------------------------------------------------------------------------------ Layer 2
;; GitHub PR status mapping (pure functions)

(defn pr-status-from-provider
  "Map GitHub PR provider data to normalized PR status keyword.
   Input: map with :state, :isDraft, :reviewDecision keys."
  [pr]
  (let [state (some-> (:state pr) str str/upper-case)
        draft? (boolean (:isDraft pr))
        decision (some-> (:reviewDecision pr) str str/upper-case)]
    (cond
      (and state (not= "OPEN" state)) :closed
      draft? :draft
      (= "APPROVED" decision) :approved
      (= "CHANGES_REQUESTED" decision) :changes-requested
      (= "REVIEW_REQUIRED" decision) :reviewing
      :else :open)))

(defn check-rollup->ci-status
  "Map GitHub statusCheckRollup to normalized CI status keyword."
  [rollup]
  (let [entries (cond
                  (nil? rollup) []
                  (sequential? rollup) rollup
                  :else [rollup])
        conclusions (keep #(some-> (:conclusion %) str str/upper-case) entries)
        statuses (keep #(some-> (:status %) str str/upper-case) entries)]
    (cond
      (some failed-check-conclusions conclusions) :failed
      (some pending-check-states statuses) :running
      (and (seq conclusions)
           (every? passing-check-conclusions conclusions)) :passed
      (seq entries) :pending
      :else :pending)))

(defn provider-pr->train-pr
  "Convert a GitHub provider PR map to a normalized TrainPR map.
   Adds :pr/repo when repo is provided."
  ([pr] (provider-pr->train-pr pr nil))
  ([pr repo]
   (cond-> {:pr/number    (:number pr)
            :pr/title     (:title pr)
            :pr/url       (:url pr)
            :pr/branch    (:headRefName pr)
            :pr/status    (pr-status-from-provider pr)
            :pr/ci-status (check-rollup->ci-status (:statusCheckRollup pr))}
     repo (assoc :pr/repo repo))))

(defn- gitlab-status
  [mr]
  (let [state (some-> (:state mr) str str/lower-case)
        draft? (or (true? (:draft mr))
                   (true? (:work_in_progress mr))
                   (str/starts-with? (str/lower-case (or (:title mr) "")) "draft:"))
        merge-status (some-> (:merge_status mr) str str/lower-case)
        conflicts? (true? (:has_conflicts mr))]
    (cond
      (not= "opened" state) :closed
      draft? :draft
      conflicts? :changes-requested
      (contains? #{"can_be_merged" "mergeable"} merge-status) :merge-ready
      :else :open)))

(defn- gitlab-ci-status
  [mr]
  (let [status (some-> (or (get-in mr [:head_pipeline :status])
                           (get-in mr [:pipeline :status]))
                       str
                       str/lower-case)]
    (case status
      ("success" "passed") :passed
      ("failed" "canceled" "cancelled" "skipped") :failed
      ("running" "pending" "created" "preparing" "waiting_for_resource") :running
      :pending)))

(defn- gitlab-mr->train-pr
  [mr repo]
  {:pr/number    (:iid mr)
   :pr/title     (:title mr)
   :pr/url       (:web_url mr)
   :pr/branch    (:source_branch mr)
   :pr/status    (gitlab-status mr)
   :pr/ci-status (gitlab-ci-status mr)
   :pr/repo      repo})

;------------------------------------------------------------------------------ Layer 3
;; GitHub CLI interaction (I/O)

(defn- run-gh
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

(defn- fetch-open-github-prs
  [repo]
  (let [repo* (provider-repo-slug repo)
        {:keys [success? out err]} (run-gh "pr" "list"
                                           "--repo" repo*
                                           "--state" "open"
                                           "--json" "number,title,url,state,headRefName,isDraft,reviewDecision,statusCheckRollup")]
    (if-not success?
      (result-failure (gh-error-message out err) {:repo repo :provider :github})
      (try
        (let [rows (json/parse-string out true)]
          (result-success
           {:repo repo
            :provider :github
            :prs (->> rows
                      (map #(provider-pr->train-pr % repo))
                      (sort-by :pr/number)
                      vec)}))
        (catch Exception e
          (result-failure (str "Failed to parse PR list for " repo ".")
                          {:repo repo :provider :github :error (.getMessage e)}))))))

(defn- fetch-open-gitlab-mrs
  [repo]
  (let [repo* (provider-repo-slug repo)
        endpoint (str "projects/" (url-encode repo*)
                      "/merge_requests?state=opened&per_page=100&with_merge_status_recheck=true")
        {:keys [success? out err]} (run-glab "api" endpoint)]
    (if-not success?
      (result-failure (gh-error-message out err) {:repo repo :provider :gitlab})
      (try
        (let [rows (json/parse-string out true)]
          (result-success
           {:repo repo
            :provider :gitlab
            :prs (->> rows
                      (map #(gitlab-mr->train-pr % repo))
                      (sort-by :pr/number)
                      vec)}))
        (catch Exception e
          (result-failure (str "Failed to parse merge request list for " repo ".")
                          {:repo repo :provider :gitlab :error (.getMessage e)}))))))

(defn fetch-open-prs
  "Fetch open PR/MR items for a single configured repository.
   GitHub repos use `gh pr list`; GitLab repos use `glab api`.
   Returns {:success? bool :repo str :prs [TrainPR ...]}."
  [repo]
  (case (repo-provider repo)
    :gitlab (fetch-open-gitlab-mrs repo)
    (fetch-open-github-prs repo)))

(defn fetch-all-fleet-prs
  "Fetch open PRs for all configured fleet repositories.
   Returns flat vector of TrainPR maps with :pr/repo set.

   Options:
   - :config-path - Override config file path (for testing)"
  [& [{:keys [config-path]}]]
  (let [repos (get-configured-repos (or config-path default-fleet-config-path))]
    (if (empty? repos)
      []
      (->> repos
           (pmap fetch-open-prs)
           (filter :success?)
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
    (if-not (:success? result)
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
              existing (->> (get-in cfg [:fleet :repos] [])
                            (map normalize-repo-slug)
                            (filter valid-repo-slug?)
                            vec)
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

(def ^:private viewer-repos-graphql-query
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

(defn- run-glab
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

(defn- parse-gh-full-name-repos
  [out limit]
  (->> (json/parse-string out true)
       (keep :full_name)
       (map normalize-repo-slug)
       (filter valid-repo-slug?)
       distinct
       (take limit)
       vec))

(defn- list-viewer-repos
  "List repos visible to the authenticated user across affiliations."
  [limit]
  (try
    (loop [after nil
           acc []]
      (let [remaining (- limit (count acc))
            per-page (max 1 (min 100 remaining))
            args (cond-> ["api" "graphql"
                          "-f" (str "query=" viewer-repos-graphql-query)
                          "-F" (str "perPage=" per-page)]
                   after (conj "-F" (str "after=" after)))
            result (apply run-gh args)]
        (if-not (:success? result)
          (result-failure (gh-error-message (:out result) (:err result)))
          (let [parsed-result (try
                                {:ok (json/parse-string (:out result) true)}
                                (catch Exception e
                                  {:error (.getMessage e)}))]
            (if-let [err (:error parsed-result)]
              (result-failure "Failed to parse repository list."
                              {:error err})
              (let [parsed (:ok parsed-result)
                    nodes (or (get-in parsed [:data :viewer :repositories :nodes] []) [])
                    page-info (or (get-in parsed [:data :viewer :repositories :pageInfo] {}) {})
                    repos (->> nodes
                               (keep :nameWithOwner)
                               (map normalize-repo-slug)
                               (filter valid-repo-slug?)
                               distinct
                               vec)
                    next-acc (->> (concat acc repos) distinct (take limit) vec)
                    has-next? (boolean (:hasNextPage page-info))
                    cursor (:endCursor page-info)]
                (if (and has-next? cursor (< (count next-acc) limit))
                  (recur cursor next-acc)
                  (result-success {:owner nil :provider :github :repos next-acc}))))))))
    (catch Exception e
      (result-failure "Failed to list accessible repositories."
                      {:error (ex-msg e)}))))

(defn- list-github-owner-repos
  [owner limit]
  (let [owner* (some-> owner str str/trim not-empty)
        org-endpoint (str "orgs/" owner* "/repos?per_page=100&type=all&sort=updated")
        org-result (run-gh "api" org-endpoint)
        user-endpoint (str "users/" owner* "/repos?per_page=100&type=owner&sort=updated")
        result (if (:success? org-result)
                 org-result
                 (run-gh "api" user-endpoint))]
    (if-not (:success? result)
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

(defn- list-github-viewer-orgs
  []
  (let [result (run-gh "api" "user/orgs?per_page=100")]
    (if-not (:success? result)
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

(defn- list-github-user-repos-fallback
  [limit]
  (let [result (run-gh "api" "user/repos?per_page=100&sort=updated")]
    (if-not (:success? result)
      (result-failure (gh-error-message (:out result) (:err result))
                      {:provider :github :error-source :rest})
      (try
        (result-success {:owner nil
                         :provider :github
                         :repos (parse-gh-full-name-repos (:out result) limit)})
        (catch Exception e
          (result-failure "Failed to parse repository list."
                          {:owner nil :provider :github :error-source :rest :error (ex-msg e)}))))))

(defn- list-github-accessible-repos
  [limit]
  (let [viewer (list-viewer-repos limit)
        orgs-result (list-github-viewer-orgs)
        org-results (if (:success? orgs-result)
                      (->> (:orgs orgs-result)
                           (map #(list-github-owner-repos % limit))
                           doall)
                      [])
        repos (->> (concat (or (:repos viewer) [])
                           (mapcat #(or (:repos %) []) (filter :success? org-results)))
                   distinct
                   (take limit)
                   vec)
        warnings (vec (concat
                       (when-not (:success? viewer)
                         [(or (:error viewer) "GraphQL browse failed.")])
                       (when-not (:success? orgs-result)
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
        (if (:success? fallback)
          (cond-> fallback
            (seq warnings) (assoc :warnings warnings))
          (result-failure (or (:error fallback)
                              (:error viewer)
                              (:error orgs-result)
                              "Failed to list accessible repositories.")
                          {:owner nil :provider :github :error-source :rest}))))))

(defn- parse-glab-project-repos
  [out limit]
  (->> (json/parse-string out true)
       (keep :path_with_namespace)
       (map (fn [path]
              (str "gitlab:" (normalize-repo-slug path))))
       (filter valid-repo-slug?)
       distinct
       (take limit)
       vec))

(defn- list-gitlab-repos
  [{:keys [owner limit]}]
  (let [owner* (some-> owner str str/trim not-empty)
        endpoint (if owner*
                   (str "groups/" (url-encode owner*)
                        "/projects?include_subgroups=true&archived=false&per_page=100&simple=true&order_by=last_activity_at&sort=desc")
                   "projects?membership=true&archived=false&per_page=100&simple=true&order_by=last_activity_at&sort=desc")
        result (run-glab "api" endpoint)]
    (if-not (:success? result)
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
                           (when-not (:success? gh-result)
                             [(str "GitHub: " (or (:error gh-result) "browse failed"))])
                           (when-not (:success? gl-result)
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

  ;; Status mapping
  (pr-status-from-provider {:state "OPEN" :isDraft false :reviewDecision "APPROVED"})
  ;; => :approved

  (check-rollup->ci-status [{:conclusion "SUCCESS"} {:conclusion "SUCCESS"}])
  ;; => :passed

  :leave-this-here)
