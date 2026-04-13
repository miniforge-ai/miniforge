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

(ns ai.miniforge.tui-views.persistence.pr
  "PR-related persistence: policy-pack loading, PR enrichment, fleet repo
   discovery, and PR fetching.

   Extracted from persistence.clj to respect the 3-layer-max rule.

   Layer 0: PR enrichment (policy packs, readiness/risk)
   Layer 1: PR loading (fleet repos, PR items)
   Layer 2: Composite loaders (load-all-into-model)"
  (:require
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.persistence :as persistence]
   [ai.miniforge.tui-views.persistence.pr-cache :as pr-cache]
   [ai.miniforge.pr-sync.interface :as pr-sync]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.policy-pack.interface :as policy-pack]))

;------------------------------------------------------------------------------ Layer 0
;; PR enrichment

(defn packs-dir
  "Get the policy packs directory path."
  []
  (io/file (config/miniforge-home) "packs"))

(defn load-policy-packs
  "Load policy packs from ~/.miniforge/packs/.
   Returns vector of PackManifest maps, or empty vec on error."
  []
  (try
    (let [dir (packs-dir)]
      (if (and (.exists dir) (.isDirectory dir))
        (let [result (policy-pack/load-all-packs (.getPath dir))]
          (get result :loaded []))
        []))
    (catch Exception _ [])))

(defn enrich-pr-in-context
  "Enrich a single PR with readiness and risk, using its repo-level
   train snapshot as context (so dependency and fanout factors are correct).
   Passes real change-size data from the PR to the risk assessment."
  [train-snapshot pr]
  (try
    (let [readiness (pr-train/explain-readiness train-snapshot pr)
          pr-data {:change-size {:additions (get pr :pr/additions 0)
                                 :deletions (get pr :pr/deletions 0)}}
          risk (pr-train/assess-risk train-snapshot pr pr-data {} {})]
      (assoc pr
             :pr/readiness readiness
             :pr/risk risk))
    (catch Exception _
      ;; If enrichment fails, return PR unchanged (fallback to naive derivation)
      pr)))

(defn enrich-prs
  "Enrich a collection of PRs with readiness and risk from pr-train component.
   Groups PRs by repo and builds per-repo train snapshots so that dependency,
   fanout, and merge-order factors are computed correctly within each repo."
  [prs]
  (let [by-repo (group-by :pr/repo prs)
        enriched (mapcat (fn [[_repo repo-prs]]
                           (let [snapshot {:train/prs (vec repo-prs)}]
                             (mapv (partial enrich-pr-in-context snapshot) repo-prs)))
                         by-repo)
        ;; Preserve original ordering
        enriched-map (into {}
                           (map (fn [pr] [[(:pr/repo pr) (:pr/number pr)] pr]))
                           enriched)]
    (mapv (fn [pr] (get enriched-map [(:pr/repo pr) (:pr/number pr)] pr)) prs)))

;------------------------------------------------------------------------------ Layer 1
;; PR loading

(defn load-fleet-repos
  "Load configured fleet repositories from config.
   Returns vector of normalized repo slugs, or empty vec on error."
  [& [{:keys [config-path]}]]
  (try
    (if config-path
      (pr-sync/get-configured-repos config-path)
      (pr-sync/get-configured-repos))
    (catch Exception _ [])))

(defn load-fleet-repos-into-model
  "Load configured fleet repos and merge into model."
  [model & [opts]]
  (let [repos (load-fleet-repos opts)]
    (assoc model :fleet-repos (vec repos))))

(defn load-pr-items
  "Fetch PRs for all configured fleet repositories.
   Enriches each PR with readiness and risk from pr-train component.
   Returns {:prs [...] :error nil} on success, {:prs [] :error \"msg\"} on failure.

   Options:
   - :config-path - Override config file path
   - :state       - :open (default), :closed, :merged, :all"
  [& [{:keys [config-path state]}]]
  (try
    (let [opts (cond-> {}
                 config-path (assoc :config-path config-path)
                 state       (assoc :state state))
          prs (pr-sync/fetch-all-fleet-prs (when (seq opts) opts))]
      {:prs (enrich-prs prs) :error nil})
    (catch Exception e
      {:prs [] :error (.getMessage e)})))

(defn load-pr-items-into-model
  "Load PRs from configured repos and merge into model.
   Applies cached policy and risk analysis results to avoid re-running
   expensive evaluations on startup.

   Arguments:
   - model - The TUI model
   - opts  - Options: :config-path

   Returns: Updated model with :pr-items populated."
  [model & [opts]]
  (let [{:keys [prs error]} (load-pr-items opts)
        cache (pr-cache/read-cache)]
    (cond
      (seq prs)
      (let [prs-with-cache (pr-cache/apply-cached-policy prs cache)
            cached-risk (pr-cache/apply-cached-agent-risk prs cache)
            cache-hits (count (filter :pr/policy prs-with-cache))
            fresh (- (count prs-with-cache) cache-hits)]
        (-> model
            (assoc :pr-items (vec prs-with-cache))
            (cond-> (seq cached-risk) (assoc :agent-risk cached-risk))
            (assoc :last-updated (java.util.Date.))
            (assoc :flash-message
                   (str "Loaded " (count prs) " PRs from "
                        (count (distinct (map :pr/repo prs))) " repo(s)"
                        (when (pos? cache-hits)
                          (str " (" cache-hits " cached, " fresh " need eval)"))))))

      error
      (assoc model :flash-message (str "PR sync failed: " error))

      :else
      (assoc model :flash-message "No open PRs found in fleet repos"))))

(defn discover-repos
  "Discover repos from a GitHub org/user and add to fleet config.
   Returns result map from pr-sync/discover-repos!."
  [owner]
  (try
    (pr-sync/discover-repos! {:owner owner})
    (catch Exception e
      {:success? false :error (.getMessage e)})))

(defn browse-repos
  "Browse repositories from providers (read-only).
   Returns result map from pr-sync/list-org-repos."
  [& [{:keys [owner limit provider] :or {limit 100 provider :github}}]]
  (try
    (pr-sync/list-org-repos (cond-> {}
                              owner (assoc :owner owner)
                              provider (assoc :provider provider)
                              (integer? limit) (assoc :limit limit)))
    (catch Exception e
      {:success? false :owner owner :provider provider :error (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 2
;; Composite loaders

(defn load-all-into-model
  "Load both workflows and PRs into model on startup.

   Arguments:
   - model - The initial TUI model
   - opts  - {:limit N :config-path path}

   Returns: Updated model with :workflows and :pr-items populated."
  [model & [opts]]
  (-> model
      (persistence/load-workflows-into-model opts)
      (load-fleet-repos-into-model opts)
      (load-pr-items-into-model opts)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load PRs
  (def m2 (load-pr-items-into-model (model/init-model)))
  (count (:pr-items m2))

  ;; Load fleet repos
  (load-fleet-repos)

  ;; Load policy packs
  (load-policy-packs)

  :leave-this-here)
