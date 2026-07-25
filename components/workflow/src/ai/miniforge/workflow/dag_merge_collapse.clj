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
(ns ai.miniforge.workflow.dag-merge-collapse
  "The v2 multi-parent merge's ancestor-collapse graph algorithm
   (miniforge#1317 split of `dag-merge`): snapshot each declared
   parent's branch tip to a SHA, then drop any parent whose tip is
   reachable from another parent's tip (spec §3.2 steps 1 and 4).
   Pure graph analysis over git — no merge execution, no anomaly
   dispatch beyond what snapshotting itself can fail with."
  (:require
   [ai.miniforge.workflow.dag-merge-anomaly :as anomaly]
   [ai.miniforge.workflow.dag-merge-git :as merge-git]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} snapshot-parent-shas
  "Resolve each parent's branch tip to a SHA at this moment (spec §3.2
   step 1). Returns the parents vector with `:commit-sha` populated, or
   an anomaly when any branch can't be resolved."
  [host-repo parents]
  ;; Validate ALL parent ref-names before any git I/O, so a structurally
  ;; invalid name deterministically surfaces branch-name-invalid regardless
  ;; of parent order — otherwise a valid-but-unresolvable sibling that sorts
  ;; first would mask it (parents derive from a dep SET, so order varies).
  (if-let [invalid (first (remove #(anomaly/valid-ref-name? (:branch %)) parents))]
    (anomaly/branch-name-invalid-anomaly invalid)
    (loop [remaining parents
           out (transient [])]
      (if-let [p (first remaining)]
        (let [r (merge-git/run-git host-repo "rev-parse" "--verify" (str (:branch p) "^{commit}"))]
          (if (zero? (:exit r))
            (recur (rest remaining)
                   (conj! out (assoc p :commit-sha (str/trim (:out r)))))
            (anomaly/branch-unresolvable-anomaly p r)))
        {:parents (persistent! out)}))))

(defn ^{:stratum 0} ancestor-of?
  "True when `sha-a` is an ancestor of (reachable from) `sha-b`."
  [host-repo sha-a sha-b]
  (zero? (:exit (merge-git/run-git host-repo "merge-base" "--is-ancestor" sha-a sha-b))))

(defn ^{:stratum 0} maximal-tip?
  "True when `p`'s commit-sha is NOT reachable from any other parent's
   commit-sha. Maximal tips survive the spec §3.2 step-4 ancestor
   collapse; ancestors-of-other-parents are dropped because their
   contributions are already included in the descendants."
  [p parents ancestor?-fn]
  (not-any? (fn ancestor-relates? [other]
              (and (not= (:task/id other) (:task/id p))
                   (not= (:commit-sha other) (:commit-sha p))
                   (ancestor?-fn (:commit-sha p) (:commit-sha other))))
            parents))

(defn ^{:stratum 0} find-absorber
  "Among `survivors`, find the first whose tip is a descendant of
   `dropped`'s tip. That's the parent that absorbed `dropped`'s
   contribution during ancestor collapse."
  [dropped survivors ancestor?-fn]
  (->> survivors
       (filter (fn descends-from-dropped? [s]
                 (ancestor?-fn (:commit-sha dropped) (:commit-sha s))))
       first))

(defn ^{:stratum 0} shared-ancestry?
  "True when at least one common ancestor exists across all parent SHAs.
   For the 3+-parent case git's merge-base requires --octopus to find
   the n-way common ancestor (the default only handles two heads)."
  [host-repo parents]
  (let [shas (mapv :commit-sha parents)
        args (cond-> ["merge-base"]
               (> (count shas) anomaly/merge-base-default-max-parents) (conj "--octopus")
               :always (into shas))
        r (apply merge-git/run-git host-repo args)]
    (and (zero? (:exit r))
         (not (str/blank? (:out r))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} collapse-record
  "Build a single `{:dropped :absorbed-into}` record for the
   collapse-ancestors audit log."
  [dropped survivors ancestor?-fn]
  {:dropped       (:task/id dropped)
   :absorbed-into (:task/id (find-absorber dropped survivors ancestor?-fn))})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} collapse-ancestors
  "Spec §3.2 step 4. Drop any parent whose tip is reachable from
   another parent's tip; preserve order among surviving maximal tips.
   Returns `{:parents [...] :collapsed [{:dropped :absorbed-into}]}`."
  [host-repo parents]
  (let [ancestor? (memoize (fn [a b] (ancestor-of? host-repo a b)))
        survivors (filterv #(maximal-tip? % parents ancestor?) parents)
        survivor-ids (set (map :task/id survivors))
        collapsed (->> parents
                       (remove (comp survivor-ids :task/id))
                       (mapv #(collapse-record % survivors ancestor?)))]
    {:parents survivors
     :collapsed collapsed}))
