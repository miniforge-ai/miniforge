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

(ns ai.miniforge.tui-views.views.repo-manager
  "Repository manager view.

   Shows configured fleet repositories and quick management hints."
  (:require
   [clojure.string :as str]
   [ai.miniforge.tui-engine.interface.layout :as layout]
   [ai.miniforge.tui-views.model :as model]
   [ai.miniforge.tui-views.views.tab-bar :as tab-bar]))

;------------------------------------------------------------------------------ Layer 0
;; Row helpers

(defn- repo-provider
  [repo]
  (cond
    (str/starts-with? (or repo "") "gitlab:") "gitlab"
    :else "github"))

(defn- open-pr-count
  [model repo]
  (count (filter #(= repo (:pr/repo %)) (:pr-items model))))

(defn- visible-repos
  "Current repo rows after applying source mode (:fleet/:browse) and search filter."
  [model]
  (let [repos (model/repo-manager-items model)]
    (if-let [fi (:filtered-indices model)]
      (->> (vec (sort fi))
           (keep #(get repos %))
           vec)
      repos)))

(defn- format-row
  [model repo source]
  {:provider (repo-provider repo)
   :repo repo
   :open-prs (if (= source :fleet)
               (str (open-pr-count model repo))
               "-")})

;------------------------------------------------------------------------------ Layer 1
;; Rendering

(defn- render-table
  [model repos selected source theme [cols rows]]
  (if (empty? repos)
    (layout/text [cols rows]
                 (if (= source :browse)
                   "  No new remote repositories to add. Press b to refresh, f for fleet."
                   "  No repositories configured. Press b to browse remote repositories.")
                 {:fg (get theme :fg :default)})
    (layout/table [cols rows]
      {:columns [{:key :provider :header "Provider" :width 10}
                 {:key :repo :header "Repository" :width (max 16 (- cols 20))}
                 {:key :open-prs :header "Open PRs" :width 8}]
       :data (mapv #(format-row model % source) repos)
       :selected-row selected
       :header-fg (get theme :header :cyan)
       :row-fg (get theme :row-fg :default)
       :row-bg (get theme :row-bg :default)
       :selected-fg (get theme :selected-fg :white)
       :selected-bg (get theme :selected-bg :blue)})))

(defn- render-footer
  [model repos selected source [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        selected-repo (get repos selected)
        flash (:flash-message model)
        selected-count (count (:selected-ids model))]
    (layout/text [cols rows]
                 (str (if (= source :browse)
                        " j/k:nav  b:refresh-browse(all)  f:fleet  Enter:add-selected  /:search"
                        " j/k:nav  b:browse-remote(all)  x:remove-selected  Space/v/a:select  /:search  r:refresh (s:alias)")
                      (when (pos? selected-count) (str "  │ selected: " selected-count))
                      (when selected-repo (str "  │ selected: " selected-repo))
                      (when flash (str "  │ " flash)))
                 {:fg (get theme :fg-dim :default)})))

(defn render
  "Render the repo manager view."
  [model [cols rows]]
  (let [theme (or (:resolved-theme model) {})
        source (if (= :browse (:repo-manager-source model)) :browse :fleet)
        repos (visible-repos model)
        selected (when (seq repos)
                   (min (or (:selected-idx model) 0) (dec (count repos))))
        fleet-count (count (model/fleet-repos model))
        browse-count (count (model/browse-candidate-repos model))]
    (layout/split-v [cols rows] (/ 2.0 rows)
      (fn [[c r]]
        (tab-bar/render model
                        (str "Mode: " (name source)
                             "  Configured: " fleet-count
                             "  Browse candidates: " browse-count
                             (when (:browse-repos-loading? model) "  (loading...)"))
                        [c r]))
      (fn [[c r]]
        (layout/split-v [c r] (/ (- r 2.0) r)
          (fn [size] (render-table model repos selected source theme size))
          (fn [size] (render-footer model repos selected source size)))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  :leave-this-here)
