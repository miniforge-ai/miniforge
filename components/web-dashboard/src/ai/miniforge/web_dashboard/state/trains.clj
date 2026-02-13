;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.trains
  "PR Train, DAG, and fleet repository onboarding/sync accessors."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.state.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Fleet repository config and provider helpers

(def ^:private default-fleet-config-path
  (str (System/getProperty "user.home") "/.miniforge/config.edn"))

(def ^:private external-train-prefix
  "External PRs: ")

(def ^:private external-dag-name
  "External PR Fleet")

(def ^:private repo-slug-pattern
  #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

(def ^:private failed-check-conclusions
  #{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED" "STARTUP_FAILURE"})

(def ^:private passing-check-conclusions
  #{"SUCCESS" "NEUTRAL" "SKIPPED"})

(def ^:private pending-check-states
  #{"PENDING" "QUEUED" "IN_PROGRESS" "WAITING" "REQUESTED"})

(defn- result-success
  [data]
  (merge {:success? true
          :success true}
         data))

(defn- result-failure
  ([message]
   (result-failure message nil))
  ([message data]
   (merge {:success? false
           :success false
           :error message
           :anomaly (response/make-anomaly :anomalies/fault message)}
          data)))

(defn- result-exception
  ([message ex]
   (result-exception message ex nil))
  ([message ex data]
   (merge {:success? false
           :success false
           :error message
           :anomaly (response/from-exception ex)}
          data)))

(defn- gh-error-message
  [out err]
  (str/trim (if (str/blank? err) out err)))

(defn- load-fleet-config
  []
  (try
    (if (.exists (io/file default-fleet-config-path))
      (edn/read-string (slurp default-fleet-config-path))
      {:fleet {:repos []}})
    (catch Exception _
      {:fleet {:repos []}})))

(defn- save-fleet-config!
  [config]
  (let [file (io/file default-fleet-config-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str config))))

(defn- normalize-repo-slug
  [repo]
  (-> (str repo)
      str/trim
      str/lower-case))

(defn- valid-repo-slug?
  [repo]
  (boolean (re-matches repo-slug-pattern repo)))

(defn get-configured-repos
  "Get configured fleet repositories from ~/.miniforge/config.edn."
  [_state]
  (->> (get-in (load-fleet-config) [:fleet :repos] [])
       (map normalize-repo-slug)
       (filter valid-repo-slug?)
       distinct
       vec))

(defn add-configured-repo!
  "Add a repository slug (owner/name) to fleet configuration."
  [_state repo]
  (let [repo* (normalize-repo-slug repo)]
    (cond
      (str/blank? repo*)
      (result-failure
       "Repository is required. Use owner/name."
       {:repo repo*})

      (not (valid-repo-slug? repo*))
      (result-failure
       "Invalid repository format. Expected owner/name."
       {:repo repo*})

      :else
      (let [cfg (load-fleet-config)
            repos (->> (get-in cfg [:fleet :repos] [])
                       (map normalize-repo-slug)
                       (filter valid-repo-slug?)
                       vec)
            exists? (some #{repo*} repos)
            next-repos (if exists? repos (conj repos repo*))
            next-cfg (assoc-in cfg [:fleet :repos] (vec (distinct next-repos)))]
        (save-fleet-config! next-cfg)
        (result-success
         {:added? (not exists?)
          :repo repo*
          :repos (get-in next-cfg [:fleet :repos])})))))

(defn- run-gh
  [& args]
  (let [{:keys [exit out err]} (apply shell/sh "gh" args)]
    {:success? (zero? exit)
     :out (or out "")
     :err (or err "")}))

(defn- gh-api-json
  [endpoint]
  (let [{:keys [success? out err]} (run-gh "api" endpoint)]
    (if success?
      (try
        (result-success
         {:data (json/parse-string out true)})
        (catch Exception e
          (result-exception
           "Failed to parse provider response."
           e
           {:endpoint endpoint})))
      (result-failure
       (gh-error-message out err)
       {:endpoint endpoint}))))

(defn discover-configured-repos!
  "Discover repositories from GitHub and add them to fleet config.

   Options map supports:
   - :owner - org/user owner to scope discovery (optional)
   - :limit - max repos to ingest (default 50)."
  [_state {:keys [owner limit]
           :or {limit 50}}]
  (let [owner* (some-> owner str/trim not-empty)
        endpoint (if owner*
                   (str "orgs/" owner* "/repos?per_page=100")
                   "user/repos?per_page=100")
        result (gh-api-json endpoint)]
    (if-not (:success? result)
      result
      (let [repos (->> (:data result)
                       (keep :full_name)
                       (map normalize-repo-slug)
                       (filter valid-repo-slug?)
                       distinct
                       (take limit)
                       vec)
            cfg (load-fleet-config)
            existing (->> (get-in cfg [:fleet :repos] [])
                          (map normalize-repo-slug)
                          (filter valid-repo-slug?)
                          vec)
            merged (vec (distinct (concat existing repos)))
            added (vec (remove (set existing) merged))
            next-cfg (assoc-in cfg [:fleet :repos] merged)]
        (save-fleet-config! next-cfg)
        (result-success
         {:owner owner*
          :discovered (count repos)
          :added (count added)
          :repos merged
          :added-repos added})))))

(defn- pr-status-from-provider
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

(defn- check-rollup->ci-status
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

(defn- provider-pr->train-pr
  [pr]
  {:pr/number (:number pr)
   :pr/title (:title pr)
   :pr/url (:url pr)
   :pr/branch (:headRefName pr)
   :pr/status (pr-status-from-provider pr)
   :pr/ci-status (check-rollup->ci-status (:statusCheckRollup pr))})

(defn- parse-provider-pr-list
  [repo out]
  (try
    (let [rows (json/parse-string out true)]
      (result-success
       {:repo repo
        :prs (->> rows
                  (map provider-pr->train-pr)
                  (sort-by :pr/number)
                  vec)}))
    (catch Exception e
      (result-exception
       (str "Failed to parse PR list for " repo ".")
       e
       {:repo repo}))))

(defn- fetch-open-prs
  [repo]
  (let [{:keys [success? out err]} (run-gh "pr" "list"
                                            "--repo" repo
                                            "--state" "open"
                                            "--json" "number,title,url,state,headRefName,isDraft,reviewDecision,statusCheckRollup")]
    (if-not success?
      (result-failure
       (gh-error-message out err)
       {:repo repo})
      (parse-provider-pr-list repo out))))

;------------------------------------------------------------------------------ Layer 1
;; PR Train and DAG state

(def get-trains
  "Get all PR trains (cached 10s)."
  (core/ttl-memoize 10000
                    (fn [state]
                      (if-let [mgr (:pr-train-manager @state)]
                        (or (core/safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])
                        []))))

(defn get-train-detail
  "Get detailed view of a PR train."
  [state train-id]
  (if-let [mgr (:pr-train-manager @state)]
    (or (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr (parse-uuid train-id))
        {:error "Train not found"})
    {:error "PR train manager not available"}))

(defn train-action!
  "Execute action on a PR train."
  [state train-id action]
  (when-let [mgr (:pr-train-manager @state)]
    (let [tid (parse-uuid train-id)]
      (case action
        "pause" (core/safe-call 'ai.miniforge.pr-train.interface 'pause-train mgr tid "Manual pause")
        "resume" (core/safe-call 'ai.miniforge.pr-train.interface 'resume-train mgr tid)
        "merge-next" (core/safe-call 'ai.miniforge.pr-train.interface 'merge-next mgr tid)
        nil))))

(def get-dags
  "Get all repository DAGs (cached 10s)."
  (core/ttl-memoize 10000
                    (fn [state]
                      (if-let [mgr (:repo-dag-manager @state)]
                        (or (core/safe-call 'ai.miniforge.repo-dag.interface 'get-all-dags mgr) [])
                        []))))

;------------------------------------------------------------------------------ Layer 2
;; External PR onboarding and sync

(defn- ensure-default-dag-id!
  [state]
  (or (:fleet/default-dag-id @state)
      (let [mgr (:repo-dag-manager @state)
            existing (when mgr
                       (some (fn [dag]
                               (when (= external-dag-name (:dag/name dag))
                                 (:dag/id dag)))
                             (or (core/safe-call 'ai.miniforge.repo-dag.interface 'get-all-dags mgr) [])))
            created (when (and mgr (nil? existing))
                      (some-> (core/safe-call 'ai.miniforge.repo-dag.interface 'create-dag mgr external-dag-name
                                              "Externally discovered repositories and PR trains")
                              :dag/id))
            dag-id (or existing created (random-uuid))]
        (swap! state assoc :fleet/default-dag-id dag-id)
        dag-id)))

(defn- ensure-repo-in-dag!
  [state dag-id repo]
  (when-let [mgr (:repo-dag-manager @state)]
    (let [dag (core/safe-call 'ai.miniforge.repo-dag.interface 'get-dag mgr dag-id)
          exists? (some #(= repo (:repo/name %)) (:dag/repos dag))]
      (when-not exists?
        (let [[org _name] (str/split repo #"/" 2)]
          (try
            (core/safe-call 'ai.miniforge.repo-dag.interface 'add-repo
                            mgr dag-id
                            {:repo/url (str "https://github.com/" repo)
                             :repo/name repo
                             :repo/org org
                             :repo/type :application
                             :repo/default-branch "main"})
            (catch Exception _ nil)))))))

(defn- train-name-for-repo
  [repo]
  (str external-train-prefix repo))

(defn- find-existing-repo-train-id
  [state repo]
  (if-let [mgr (:pr-train-manager @state)]
    (let [expected (train-name-for-repo repo)]
      (some (fn [train]
              (when (= expected (:train/name train))
                (:train/id train)))
            (or (core/safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])))
    nil))

(defn- ensure-repo-train!
  [state repo]
  (when-let [mgr (:pr-train-manager @state)]
    (let [known-id (get-in @state [:fleet/repo-trains repo])
          known-train (when known-id
                        (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr known-id))
          existing-id (or (when (and known-id known-train) known-id)
                          (find-existing-repo-train-id state repo))
          train-id (or existing-id
                       (core/safe-call 'ai.miniforge.pr-train.interface 'create-train
                                       mgr
                                       (train-name-for-repo repo)
                                       (ensure-default-dag-id! state)
                                       (str "Externally managed PR train for " repo))) ]
      (when train-id
        (swap! state assoc-in [:fleet/repo-trains repo] train-id)
        train-id))))

(defn- prs->status-map
  [prs]
  (into {}
        (map (fn [pr]
               [(:pr/number pr)
                {:pr/status (:pr/status pr)
                 :pr/ci-status (:pr/ci-status pr)}])
             prs)))

(defn- train-sync-plan
  [before-train prs]
  (let [open-numbers (set (map :pr/number prs))
        existing-numbers (set (map :pr/number (:train/prs before-train)))]
    {:to-add (->> prs
                  (remove #(contains? existing-numbers (:pr/number %)))
                  vec)
     :to-remove (->> existing-numbers
                     (remove open-numbers)
                     vec)
     :status-map (prs->status-map prs)}))

(defn- add-prs!
  [mgr train-id repo prs]
  (doseq [pr prs]
    (core/safe-call 'ai.miniforge.pr-train.interface 'add-pr
                    mgr train-id repo (:pr/number pr) (:pr/url pr)
                    (:pr/branch pr) (:pr/title pr))))

(defn- remove-prs!
  [mgr train-id pr-nums]
  (doseq [pr-num pr-nums]
    (core/safe-call 'ai.miniforge.pr-train.interface 'remove-pr mgr train-id pr-num)))

(defn- apply-sync-plan!
  [mgr train-id repo {:keys [to-add to-remove status-map]}]
  ;; Side effects are intentionally ordered: membership first, then status, then dependency linking.
  (add-prs! mgr train-id repo to-add)
  (remove-prs! mgr train-id to-remove)
  (core/safe-call 'ai.miniforge.pr-train.interface 'sync-pr-status mgr train-id status-map)
  (core/safe-call 'ai.miniforge.pr-train.interface 'link-prs mgr train-id))

(defn- sync-repo-prs-into-train!
  [state repo]
  (let [fetch-result (fetch-open-prs repo)]
    (if-not (:success? fetch-result)
      (merge fetch-result {:repo repo})
      (if-let [mgr (:pr-train-manager @state)]
        (if-let [train-id (ensure-repo-train! state repo)]
          (let [dag-id (ensure-default-dag-id! state)
                _ (ensure-repo-in-dag! state dag-id repo)
                prs (:prs fetch-result)
                before-train (or (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr train-id)
                                 {:train/prs []})
                sync-plan (train-sync-plan before-train prs)
                _ (apply-sync-plan! mgr train-id repo sync-plan)
                after-train (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr train-id)]
            (result-success
             {:repo repo
              :train-id train-id
              :added (count (:to-add sync-plan))
              :removed (count (:to-remove sync-plan))
              :open-prs (count prs)
              :tracked-prs (count (:train/prs after-train))}))
          (result-failure
           "Unable to create or locate PR train for repository."
           {:repo repo}))
        (result-failure
         "PR train manager is not available."
         {:repo repo})))))

(defn sync-configured-repos!
  "Sync configured repositories into PR trains and ingest open PRs from provider."
  [state]
  (let [repos (get-configured-repos state)]
    (cond
      (empty? repos)
      (result-failure
       "No repositories configured. Add one or discover repositories first."
       {:repos []})

      (nil? (:pr-train-manager @state))
      (result-failure
       "PR train manager is not available in this runtime."
       {:repos repos})

      :else
      (let [results (mapv #(sync-repo-prs-into-train! state %) repos)
            ok (filter :success? results)
            failed (remove :success? results)]
        (if (empty? failed)
          (result-success
           {:repos repos
            :synced (count ok)
            :failed 0
            :results results
            :summary {:added-prs (reduce + 0 (map :added ok))
                      :removed-prs (reduce + 0 (map :removed ok))
                      :tracked-prs (reduce + 0 (map :tracked-prs ok))}})
          (result-failure
           (str "Sync failed for " (count failed) " configured repos.")
           {:repos repos
            :synced (count ok)
            :failed (count failed)
            :results results
            :summary {:added-prs (reduce + 0 (map :added ok))
                      :removed-prs (reduce + 0 (map :removed ok))
                      :tracked-prs (reduce + 0 (map :tracked-prs ok))}}))))))

;------------------------------------------------------------------------------ Layer 3
;; DAG composite state

(defn get-dag-state
  "Get DAG kanban state for visualization."
  [state]
  (let [dags (get-dags state)
        trains (get-trains state)]
    {:dags dags
     :trains trains
     :repos (mapcat :dag/repos dags)
     :tasks (mapcat (fn [train]
                      (map (fn [pr]
                             {:id (:pr/number pr)
                              :repo (:pr/repo pr)
                              :title (:pr/title pr)
                              :status (case (:pr/status pr)
                                        (:draft :open) :ready
                                        :reviewing :running
                                        :merged :done
                                        :failed :blocked
                                        :blocked)
                              :train-id (:train/id train)
                              :dependencies (:pr/depends-on pr)})
                           (:train/prs train)))
                    trains)}))
