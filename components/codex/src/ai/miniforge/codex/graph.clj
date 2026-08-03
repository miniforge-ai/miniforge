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
(ns ai.miniforge.codex.graph
  "Walking and viewing the codex node graph: reachability over
   routes-to/exposes edges (Codex SPEC T1 §3.2), horizon discipline
   (§2.6, §7.6), situation matching, and the per-landing / coverage views
   a response is assembled from."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} horizon-rank
  "Strategic outranks operational outranks tactical, and the ordering is
   asymmetric: wrong at a lower horizon while right at a higher one can
   succeed; the reverse cannot."
  {"strategic" 0 "operational" 1 "tactical" 2})

(defn- ^{:stratum 0} as-edge-list
  "A frontmatter edge field is either a parsed list or an inline string
   (situations emit `routes-to: <id>`); normalise to a list of maps."
  [v]
  (cond
    (string? v) [{:target v}]
    :else       v))

(defn ^{:stratum 0} match-situations
  "Situations whose id or title contains `text` (case-insensitive).
   Exact id match wins outright."
  [nodes text]
  (let [situations (filter #(= "situation" (:type %)) (vals nodes))
        norm (str/lower-case (str/trim text))]
    (or (seq (filter #(= (str/lower-case (str (:id %))) norm) situations))
        (seq (filter #(or (str/includes? (str/lower-case (str (:id %))) norm)
                          (str/includes? (str/lower-case (str (:title %))) norm))
                     situations)))))

(defn ^{:stratum 0} coverage-of
  "The response's own coverage statement (§7.5): landing count, unanchored
   count, horizon mix, and age of the newest scar among the landings. A
   response that reports only what is known creates false coverage."
  [landings]
  (let [problems (filter #(= "problem" (:type %)) landings)
        mix (frequencies (keep :horizon problems))]
    {:landing-count (count landings)
     :unanchored-count (count (filter #(empty? (:scars %)) problems))
     :horizon-mix mix
     :no-strategic-coverage? (nil? (get mix "strategic"))
     :newest-scar-date (some->> problems (mapcat :scars) (keep :date) sort last)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} edge-targets [node]
  (concat
    (for [r (as-edge-list (:routes-to node))]
      (or (:target r) (:value r)))
    (for [e (as-edge-list (:exposes node))]
      (:value e))))

(defn- ^{:stratum 1} escalated-scars
  "Scars anchoring `problem` whose cost landed above the problem's own
   horizon (§7.6.3) — the strongest confidence-lowering signal available."
  [nodes problem]
  (let [p-rank (get horizon-rank (:horizon problem))]
    (when p-rank
      (for [{:keys [value]} (:scars problem)
            :let [scar (get nodes value)
                  c-rank (get horizon-rank (:cost-horizon scar))]
            :when (and c-rank (< c-rank p-rank))]
        value))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} reachable-from
  "Every node id reachable from `start` by walking routes-to + exposes."
  [nodes start]
  (loop [seen #{} frontier [start]]
    (if-let [cur (peek frontier)]
      (if (seen cur)
        (recur seen (pop frontier))
        (recur (conj seen cur)
               (into (pop frontier)
                     (when-let [n (get nodes cur)] (edge-targets n)))))
      seen)))

(defn ^{:stratum 2} landing-view
  "The per-landing map a response carries for one problem or resolution."
  [nodes problem]
  (let [scars (for [{:keys [value]} (:scars problem)
                    :let [s (get nodes value)]
                    :when s]
                {:id (:id s) :date (:date s) :cost (:cost s)
                 :cost-horizon (:cost-horizon s) :origin (:origin s)})]
    {:id (:id problem)
     :type (:type problem)
     :title (:title problem)
     :horizon (:horizon problem)
     :confidence (:confidence problem)
     :open (mapv :value (:open problem))
     :scars (vec scars)
     :escalations (vec (escalated-scars nodes problem))}))
