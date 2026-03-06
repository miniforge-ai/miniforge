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

(def default-fleet-config-path
  (str (System/getProperty "user.home") "/.miniforge/config.edn"))

(def external-train-prefix
  "External PRs: ")

(def external-dag-name
  "External PR Fleet")

(def repo-slug-pattern
  #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

(def failed-check-conclusions
  #{"FAILURE" "TIMED_OUT" "CANCELLED" "ACTION_REQUIRED" "STARTUP_FAILURE"})

(def passing-check-conclusions
  #{"SUCCESS" "NEUTRAL" "SKIPPED"})

(def pending-check-states
  #{"PENDING" "QUEUED" "IN_PROGRESS" "WAITING" "REQUESTED"})

(def readiness-factor-order
  [:deps-merged :ci-passed :approved :gates-passed :behind-main])

(def readiness-weights
  {:deps-merged 0.25
   :ci-passed 0.25
   :approved 0.20
   :gates-passed 0.15
   :behind-main 0.15})

(def readiness-threshold 0.85)

(def ci-scores
  {:passed 1.0
   :running 0.5
   :pending 0.5
   :failed 0.0})

(def approval-scores
  {:approved 1.0
   :merged 1.0
   :merging 1.0
   :reviewing 0.5
   :changes-requested 0.25
   :open 0.30
   :draft 0.0
   :closed 0.0
   :failed 0.0})

(def blocker-rank
  {:dependency 0
   :review 1
   :ci 2
   :policy 3
   :conflict 4})

(defn now []
  (java.util.Date.))

(defn ex-msg
  [^Throwable e]
  (or (.getMessage e)
      (some-> e class .getName)
      "unknown exception"))

(defn ensure-message
  [message fallback]
  (or (some-> message str str/trim not-empty)
      fallback))

(defn normalized-limit
  [limit default]
  (let [n (if (integer? limit) limit default)]
    (if (pos? n) n default)))

(defn succeeded?
  "Check if a result map indicates success."
  [result]
  (boolean (:success? result)))

(defn result-success
  [data]
  (merge {:success? true
          :success true}
         data))

(defn result-failure
  ([message]
   (result-failure message nil))
  ([message data]
   (let [msg (ensure-message message "Operation failed with no additional details.")]
     (merge {:success? false
             :success false
             :error msg
             :anomaly (response/make-anomaly :anomalies/fault msg)}
            data))))

(defn result-exception
  ([message ex]
   (result-exception message ex nil))
  ([message ex data]
   (let [msg (ensure-message message "Operation failed due to an exception.")]
     (merge {:success? false
             :success false
             :error msg
             :exception (ex-msg ex)
             :anomaly (response/from-exception ex)}
            data))))

(defn gh-error-message
  [out err]
  (ensure-message (if (str/blank? err) out err)
                  "Provider command failed. Check authentication and repository access."))

(defn load-fleet-config
  []
  (try
    (if (.exists (io/file default-fleet-config-path))
      (edn/read-string (slurp default-fleet-config-path))
      {:fleet {:repos []}})
    (catch Exception _
      {:fleet {:repos []}})))

(defn save-fleet-config!
  [config]
  (let [file (io/file default-fleet-config-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str config))))

(defn normalize-repo-slug
  [repo]
  (-> (str repo)
      str/trim
      str/lower-case))

(defn valid-repo-slug?
  [repo]
  (boolean (re-matches repo-slug-pattern repo)))

(defn actionable-error-hint
  [error-msg]
  (let [msg (str/lower-case (or error-msg ""))]
    (cond
      (or (str/includes? msg "auth")
          (str/includes? msg "authentication")
          (str/includes? msg "not logged"))
      "Run `gh auth login` and retry."

      (or (str/includes? msg "not found")
          (str/includes? msg "forbidden")
          (str/includes? msg "permission")
          (str/includes? msg "access denied"))
      "Verify repository slug and access permissions, then retry sync."

      (or (str/includes? msg "rate limit")
          (str/includes? msg "secondary rate"))
      "Provider rate-limited this request. Wait briefly, then retry sync."

      (or (str/includes? msg "parse")
          (str/includes? msg "malformed")
          (str/includes? msg "invalid json"))
      "Provider returned malformed data. Retry sync; if it repeats, inspect provider CLI output."

      (or (str/includes? msg "timeout")
          (str/includes? msg "network")
          (str/includes? msg "connection"))
      "Transient provider/network failure. Retry sync."

      :else
      "Retry sync. If it keeps failing, run the equivalent `gh` command locally for details.")))

(defn with-actionable-error
  [result]
  (if (succeeded? result)
    result
    (assoc result :action (or (:action result)
                              (actionable-error-hint (:error result))))))

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

(defn run-gh
  [& args]
  (try
    (let [{:keys [exit out err]} (apply shell/sh "gh" args)]
      {:success? (zero? exit)
       :out (or out "")
       :err (or err "")})
    (catch Exception e
      {:success? false
       :out ""
       :err (ex-msg e)})))

(defn gh-api-json
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
           {:endpoint endpoint
            :action "Retry discovery. If this persists, check `gh api` output."})))
      (result-failure
       (gh-error-message out err)
       {:endpoint endpoint
        :action (actionable-error-hint (gh-error-message out err))}))))

(defn discover-configured-repos!
  "Discover repositories from GitHub and add them to fleet config.

   Options map supports:
   - :owner - org/user owner to scope discovery (optional)
   - :limit - max repos to ingest (default 50)."
  [_state {:keys [owner limit]
           :or {limit 50}}]
  (let [owner* (some-> owner str/trim not-empty)
        limit* (normalized-limit limit 50)
        endpoint (if owner*
                   (str "orgs/" owner* "/repos?per_page=100")
                   "user/repos?per_page=100")
        result (gh-api-json endpoint)]
    (if-not (succeeded? result)
      result
      (let [repos (->> (:data result)
                       (keep :full_name)
                       (map normalize-repo-slug)
                       (filter valid-repo-slug?)
                       distinct
                       (take limit*)
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

(defn pr-status-from-provider
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

(defn merge-state-status->behind?
  [merge-state-status]
  (contains? #{"BEHIND" "DIRTY"}
             (some-> merge-state-status str str/upper-case)))

(defn provider-pr->train-pr
  [pr]
  {:pr/number (:number pr)
   :pr/title (:title pr)
   :pr/url (:url pr)
   :pr/branch (:headRefName pr)
   :pr/status (pr-status-from-provider pr)
   :pr/ci-status (check-rollup->ci-status (:statusCheckRollup pr))
   :pr/behind-main? (merge-state-status->behind? (:mergeStateStatus pr))})

(defn parse-provider-pr-list
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
       {:repo repo
        :action "Retry sync. If this persists, inspect `gh pr list --json ...` output."}))))

(defn fetch-open-prs
  [repo]
  (let [{:keys [success? out err]} (run-gh "pr" "list"
                                            "--repo" repo
                                            "--state" "open"
                                            "--json" "number,title,url,state,headRefName,isDraft,reviewDecision,statusCheckRollup,mergeStateStatus")]
    (if-not success?
      (result-failure
       (gh-error-message out err)
       {:repo repo
        :action (actionable-error-hint (gh-error-message out err))})
      (parse-provider-pr-list repo out))))

;------------------------------------------------------------------------------ Layer 1
;; Deterministic train rendering enrichment

(defn gate-results
  [pr]
  (let [gates (:pr/gate-results pr)]
    (if (sequential? gates) gates [])))

(defn gates-passed?
  [pr]
  (let [gates (gate-results pr)]
    (or (empty? gates)
        (every? :gate/passed? gates))))

(defn gates-score
  [pr]
  (let [gates (gate-results pr)]
    (if (empty? gates)
      1.0
      (let [passed (count (filter :gate/passed? gates))]
        (double (/ passed (count gates)))))))

(defn pr-sort-key
  [pr]
  [(or (:pr/merge-order pr) Long/MAX_VALUE)
   (or (:pr/number pr) Long/MAX_VALUE)])

(defn sort-prs
  [prs]
  (->> prs
       (sort-by pr-sort-key)
       vec))

(defn pr-map
  [prs]
  (into {} (map (juxt :pr/number identity) prs)))

(defn unresolved-deps
  [prs-by-number pr]
  (->> (:pr/depends-on pr [])
       (filter #(not= :merged (:pr/status (get prs-by-number %))))
       sort
       vec))

(defn deps-score
  [prs-by-number pr]
  (let [deps (:pr/depends-on pr [])]
    (if (empty? deps)
      1.0
      (let [merged-count (count (remove #(contains? (set (unresolved-deps prs-by-number pr)) %) deps))]
        (double (/ merged-count (count deps)))))))

(defn pr-ready?
  [prs-by-number pr]
  (let [status (:pr/status pr)
        ci-passed? (= :passed (:pr/ci-status pr))
        deps-clear? (empty? (unresolved-deps prs-by-number pr))]
    (and deps-clear?
         (#{:approved :merging :merged} status)
         ci-passed?
         (gates-passed? pr)
         (not (:pr/behind-main? pr)))))

(defn readiness-state
  [prs-by-number pr]
  (let [status (:pr/status pr)]
    (cond
      (= status :merged) :merge-ready
      (= :failed (:pr/ci-status pr)) :ci-failing
      (= :changes-requested status) :changes-requested
      (seq (unresolved-deps prs-by-number pr)) :dep-blocked
      (:pr/behind-main? pr) :merge-conflicts
      (not (gates-passed? pr)) :policy-failing
      (pr-ready? prs-by-number pr) :merge-ready
      :else :needs-review)))

(defn blockers
  [prs-by-number pr]
  (let [status (:pr/status pr)
        ci-status (:pr/ci-status pr)
        unresolved (unresolved-deps prs-by-number pr)
        failed-gates (->> (gate-results pr)
                          (remove :gate/passed?)
                          count)
        raw (cond-> []
              (seq unresolved)
              (conj {:blocker/type :dependency
                     :blocker/message (str "Waiting on dependencies: "
                                           (str/join ", " (map #(str "#" %) unresolved)))
                     :blocker/source "train"})

              (= status :changes-requested)
              (conj {:blocker/type :review
                     :blocker/message "Changes requested by reviewer."
                     :blocker/source "provider"})

              (= status :draft)
              (conj {:blocker/type :review
                     :blocker/message "PR is draft and needs to be marked ready for review."
                     :blocker/source "provider"})

              (= status :reviewing)
              (conj {:blocker/type :review
                     :blocker/message "Awaiting reviewer approval."
                     :blocker/source "provider"})

              (= status :open)
              (conj {:blocker/type :review
                     :blocker/message "Awaiting review signal."
                     :blocker/source "provider"})

              (= ci-status :failed)
              (conj {:blocker/type :ci
                     :blocker/message "Required CI checks failed."
                     :blocker/source "provider"})

              (#{:running :pending} ci-status)
              (conj {:blocker/type :ci
                     :blocker/message "Required CI checks are still running."
                     :blocker/source "provider"})

              (pos? failed-gates)
              (conj {:blocker/type :policy
                     :blocker/message (str failed-gates " gate/policy checks failing.")
                     :blocker/source "policy"})

              (:pr/behind-main? pr)
              (conj {:blocker/type :conflict
                     :blocker/message "Branch is behind main and requires rebase."
                     :blocker/source "provider"}))]
    (->> raw
         (sort-by (juxt #(get blocker-rank (:blocker/type %) 999)
                        :blocker/message))
         vec)))

(defn readiness-factors
  [prs-by-number pr]
  (let [scores {:deps-merged (deps-score prs-by-number pr)
                :ci-passed (get ci-scores (:pr/ci-status pr) 0.0)
                :approved (get approval-scores (:pr/status pr) 0.0)
                :gates-passed (gates-score pr)
                :behind-main (if (:pr/behind-main? pr) 0.0 1.0)}]
    (mapv (fn [factor]
            (let [weight (get readiness-weights factor 0.0)
                  score (double (get scores factor 0.0))]
              {:factor factor
               :weight weight
               :score score
               :contribution (* weight score)}))
          readiness-factor-order)))

(defn readiness
  [prs-by-number pr]
  (let [factors (readiness-factors prs-by-number pr)
        score (transduce (map :contribution) + 0.0 factors)
        state (readiness-state prs-by-number pr)
        blocking (blockers prs-by-number pr)]
    {:readiness/state state
     :readiness/score score
     :readiness/threshold readiness-threshold
     :readiness/ready? (and (= state :merge-ready)
                            (>= score readiness-threshold))
     :readiness/factors factors
     :readiness/blockers blocking}))

(defn enrich-pr
  [prs-by-number pr]
  (let [r (readiness prs-by-number pr)]
    (assoc pr
           :pr/readiness r
           :pr/blocking-reasons (mapv :blocker/message (:readiness/blockers r)))))

(defn blocking-details
  [prs blocking-prs]
  (let [prs-by-number (pr-map prs)]
    (->> blocking-prs
         (keep (fn [pr-number]
                 (when-let [pr (get prs-by-number pr-number)]
                   {:pr/number pr-number
                    :pr/repo (:pr/repo pr)
                    :pr/title (:pr/title pr)
                    :blocking/reasons (:pr/blocking-reasons pr)})))
         vec)))

(defn readiness-summary
  [prs]
  (->> prs
       (map (fn [pr]
              (get-in pr [:pr/readiness :readiness/state] :unknown)))
       frequencies
       (into (sorted-map))))

(defn enrich-train
  [train]
  (let [sorted-prs (sort-prs (:train/prs train))
        raw-pr-map (pr-map sorted-prs)
        annotated-prs (mapv (partial enrich-pr raw-pr-map) sorted-prs)
        merge-order-by-pr (into {} (map (juxt :pr/number :pr/merge-order) annotated-prs))
        sorted-ready (->> (:train/ready-to-merge train)
                          (sort-by #(get merge-order-by-pr % Long/MAX_VALUE))
                          vec)
        sorted-blocking (->> (:train/blocking-prs train)
                             (sort-by #(get merge-order-by-pr % Long/MAX_VALUE))
                             vec)]
    (-> train
        (assoc :train/prs annotated-prs)
        (assoc :train/ready-to-merge sorted-ready)
        (assoc :train/blocking-prs sorted-blocking)
        (assoc :train/blocking-details (blocking-details annotated-prs sorted-blocking))
        (assoc :train/readiness-summary (readiness-summary annotated-prs)))))

;------------------------------------------------------------------------------ Layer 2
;; PR Train and DAG state

(def get-trains
  "Get all PR trains (cached 10s)."
  (core/ttl-memoize 10000
                    (fn [state]
                      (if-let [mgr (:pr-train-manager @state)]
                        (->> (or (core/safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])
                             (map enrich-train)
                             vec)
                        []))))

(defn get-train-detail
  "Get detailed view of a PR train."
  [state train-id]
  (if-let [mgr (:pr-train-manager @state)]
    (let [tid (try
                (parse-uuid train-id)
                (catch Exception _ nil))]
      (if-not tid
        {:error "Invalid train id."}
        (if-let [train (core/safe-call 'ai.miniforge.pr-train.interface 'get-train mgr tid)]
          (enrich-train train)
          {:error "Train not found"})))
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

;------------------------------------------------------------------------------ Layer 3
;; External PR onboarding and sync

(defn classify-error-category
  [error-msg]
  (let [msg (str/lower-case (or error-msg ""))]
    (cond
      (or (str/includes? msg "auth")
          (str/includes? msg "authentication")
          (str/includes? msg "not logged"))
      :auth

      (or (str/includes? msg "not found")
          (str/includes? msg "forbidden")
          (str/includes? msg "permission")
          (str/includes? msg "access denied"))
      :access

      (or (str/includes? msg "rate limit")
          (str/includes? msg "secondary rate"))
      :rate-limit

      (or (str/includes? msg "parse")
          (str/includes? msg "malformed")
          (str/includes? msg "invalid json"))
      :parse

      (or (str/includes? msg "timeout")
          (str/includes? msg "timed out")
          (str/includes? msg "network")
          (str/includes? msg "connection"))
      :network

      :else
      :unknown)))

(defn sync-status
  [result]
  (let [failed-repos (->> (:results result)
                          (remove succeeded?)
                          (map with-actionable-error)
                          (map (fn [entry]
                                 {:repo (:repo entry)
                                  :error (:error entry)
                                  :action (:action entry)
                                  :error-category (classify-error-category (:error entry))}))
                          (sort-by :repo)
                          vec)
        status (cond
                 (succeeded? result) :success
                 (pos? (:synced result 0)) :partial
                 :else :failed)]
    {:status status
     :timestamp (now)
     :message (:error result)
     :synced (:synced result 0)
     :failed (:failed result 0)
     :repos (:repos result)
     :summary (:summary result)
     :failures failed-repos}))

(defn record-last-sync!
  [state result]
  (swap! state assoc :fleet/last-sync (sync-status result))
  result)

(defn ensure-default-dag-id!
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

(defn ensure-repo-in-dag!
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

(defn train-name-for-repo
  [repo]
  (str external-train-prefix repo))

(defn find-existing-repo-train-id
  [state repo]
  (if-let [mgr (:pr-train-manager @state)]
    (let [expected (train-name-for-repo repo)]
      (some (fn [train]
              (when (= expected (:train/name train))
                (:train/id train)))
            (or (core/safe-call 'ai.miniforge.pr-train.interface 'list-trains mgr) [])))
    nil))

(defn ensure-repo-train!
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
                                       (str "Externally managed PR train for " repo)))]
      (when train-id
        (swap! state assoc-in [:fleet/repo-trains repo] train-id)
        train-id))))

(defn prs->status-map
  [prs]
  (into {}
        (map (fn [pr]
               [(:pr/number pr)
                {:pr/status (:pr/status pr)
                 :pr/ci-status (:pr/ci-status pr)}])
             prs)))

(defn train-sync-plan
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

(defn add-prs!
  [mgr train-id repo prs]
  (doseq [pr prs]
    (core/safe-call 'ai.miniforge.pr-train.interface 'add-pr
                    mgr train-id repo (:pr/number pr) (:pr/url pr)
                    (:pr/branch pr) (:pr/title pr))))

(defn remove-prs!
  [mgr train-id pr-nums]
  (doseq [pr-num pr-nums]
    (core/safe-call 'ai.miniforge.pr-train.interface 'remove-pr mgr train-id pr-num)))

(defn apply-sync-plan!
  [mgr train-id repo {:keys [to-add to-remove status-map]}]
  ;; Side effects are intentionally ordered: membership first, then status, then dependency linking.
  (add-prs! mgr train-id repo to-add)
  (remove-prs! mgr train-id to-remove)
  (core/safe-call 'ai.miniforge.pr-train.interface 'sync-pr-status mgr train-id status-map)
  (core/safe-call 'ai.miniforge.pr-train.interface 'link-prs mgr train-id))

(defn sync-repo-prs-into-train!
  [state repo]
  (let [fetch-result (try
                       (fetch-open-prs repo)
                       (catch Exception e
                         (result-exception
                          (str "Failed to fetch PRs for " repo ".")
                          e
                          {:repo repo})))]
    (if-not (succeeded? fetch-result)
      (-> fetch-result
          (assoc :repo repo)
          with-actionable-error)
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
           {:repo repo
            :action "Ensure PR train manager and repo DAG manager are initialized, then retry."}))
        (result-failure
         "PR train manager is not available."
         {:repo repo
          :action "Start dashboard with a PR train manager and retry sync."})))))

(defn sync-configured-repos!
  "Sync configured repositories into PR trains and ingest open PRs from provider."
  [state]
  (let [repos (get-configured-repos state)]
    (cond
      (empty? repos)
      (record-last-sync!
       state
       (result-failure
        "No repositories configured. Add one or discover repositories first."
        {:repos []
         :synced 0
         :failed 0
         :summary {:added-prs 0
                   :removed-prs 0
                   :tracked-prs 0}}))

      (nil? (:pr-train-manager @state))
      (record-last-sync!
       state
       (result-failure
        "PR train manager is not available in this runtime."
        {:repos repos
         :synced 0
         :failed (count repos)
         :summary {:added-prs 0
                   :removed-prs 0
                   :tracked-prs 0}}))

      :else
      (let [results (mapv #(sync-repo-prs-into-train! state %) repos)
            ok (->> results (filter succeeded?) vec)
            failed (->> results (remove succeeded?) (mapv with-actionable-error))
            failures (->> failed
                          (map (fn [entry]
                                 {:repo (:repo entry)
                                  :error (:error entry)
                                  :action (:action entry)}))
                          (sort-by :repo)
                          vec)
            summary {:added-prs (reduce + 0 (map :added ok))
                     :removed-prs (reduce + 0 (map :removed ok))
                     :tracked-prs (reduce + 0 (map :tracked-prs ok))}
            result (if (empty? failed)
                     (result-success
                      {:repos repos
                       :synced (count ok)
                       :failed 0
                       :failures []
                       :results results
                       :summary summary})
                     (result-failure
                      (if (seq ok)
                        (str "Sync completed with failures for " (count failed) " repo(s).")
                        (str "Sync failed for " (count failed) " configured repo(s)."))
                      {:repos repos
                       :synced (count ok)
                       :failed (count failed)
                       :failures failures
                       :results (vec (concat ok failed))
                       :summary summary
                       :partial? (boolean (seq ok))}))]
        (record-last-sync! state result)))))

;------------------------------------------------------------------------------ Layer 4
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
