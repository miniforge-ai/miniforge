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

(ns work
  "Work-spec queue rendering.

   Reads work/*.spec.edn + work/themes.edn, sorts by priority tier +
   dependency-ready status, renders to stdout and to work/QUEUE.md.

   Authoring rules for :spec/priority are enforced by
   .standards/foundations/work-spec-authoring.mdc (Dewey 021)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 0 — reading

(def ^:private tier-order
  {:blocker 0 :high 1 :medium 2 :low 3 nil 2})

(defn- read-edn [path]
  (try (edn/read-string (slurp path))
       (catch Exception e
         (binding [*out* *err*]
           (println (str "WARN: could not parse " path ": " (ex-message e))))
         nil)))

(defn- spec-files []
  (->> (fs/glob "work" "*.spec.edn")
       (map str)
       sort))

(defn- read-specs []
  (keep (fn [f]
          (when-let [m (read-edn f)]
            (assoc m :spec/source-path f
                     :spec/filename (fs/file-name f))))
        (spec-files)))

(defn- read-themes []
  (or (read-edn "work/themes.edn") {}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 1 — sorting + grouping

(defn- priority-of [spec]
  (:spec/priority spec {:tier :medium :axes #{} :rationale "unranked"}))

(defn- tier-key [spec]
  (get tier-order (:tier (priority-of spec)) 2))

(defn- theme-key [spec]
  (or (:theme (priority-of spec)) :unassigned))

(defn- blocked-by-ready?
  "A spec is ready when none of its :blocked-by specs still appear in the
   input set. Input specs are filenames as strings."
  [spec all-filenames-set]
  (not-any? all-filenames-set (:blocked-by (priority-of spec))))

(defn- sort-queue
  "Sort by (1) ready-first (2) tier asc (3) filename."
  [specs]
  (let [fnames (into #{} (map :spec/filename specs))]
    (sort-by (juxt (complement #(blocked-by-ready? % fnames))
                   tier-key
                   :spec/filename)
             specs)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 2 — rendering

(defn- render-row [spec themes]
  (let [p       (priority-of spec)
        theme   (get themes (:theme p))
        ready?  (blocked-by-ready? spec (into #{} (map :spec/filename (read-specs))))
        axes    (when (seq (:axes p)) (str/join "+" (map #(str/replace (name %) #"-" "") (:axes p))))]
    {:tier      (name (:tier p :medium))
     :ready     (if ready? "●" "○")
     :theme     (name (:theme p :unassigned))
     :theme-title (get theme :theme/title "—")
     :filename  (:spec/filename spec)
     :title     (:spec/title spec "")
     :axes      (or axes "-")
     :rationale (:rationale p "")}))

(defn- md-table [rows]
  (let [header ["tier" "r" "theme" "spec" "axes"]
        fmt   (fn [r]
                (str "| " (:tier r) " | " (:ready r) " | " (:theme r)
                     " | `" (:filename r) "` — " (:title r) " | " (:axes r) " |"))]
    (str/join "\n"
              (into [(str "| " (str/join " | " header) " |")
                     "|---|---|---|---|---|"]
                    (map fmt rows)))))

(defn- md-theme-section [theme-id theme specs themes]
  (let [rows (map #(render-row % themes) specs)
        title (or (:theme/title theme) (name theme-id))
        desc  (or (:theme/description theme) "(no theme description)")
        inf   (:theme/informative-spec theme)
        status (name (:theme/status theme :planned))]
    (str "## Theme — " title
         " (`" (name theme-id) "`, status: " status ")\n\n"
         desc "\n\n"
         (when inf (str "**Informative spec:** `" inf "`\n\n"))
         (md-table rows)
         "\n\n")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Layer 3 — tasks

(defn queue
  "Render the full prioritized queue to stdout + work/QUEUE.md.

   Legend:
     r = ready (● = no unmet :blocked-by, ○ = waiting)
     tier order: blocker → high → medium → low
     axes: dogfoodenabler / tokenconservation / observation"
  [& _]
  (let [specs   (read-specs)
        themes  (read-themes)
        grouped (group-by theme-key specs)
        md      (str "# Work-spec queue\n\n"
                     "_Generated by `bb work:queue`. Do not edit by hand._\n\n"
                     "See `work/themes.edn` for theme catalog, "
                     "`.standards/foundations/work-spec-authoring.mdc` for "
                     "authoring rules.\n\n"
                     (str/join
                       (for [[theme-id specs] (sort-by (comp #(get tier-order (:tier (priority-of (first %))) 2) second)
                                                      grouped)
                             :let [sorted (sort-queue specs)]]
                         (md-theme-section theme-id (get themes theme-id) sorted themes))))]
    (spit "work/QUEUE.md" md)
    (println md)
    (println (str "\nWrote " (count specs) " spec(s) across "
                  (count grouped) " theme(s) to work/QUEUE.md."))))

(defn theme
  "Render a single theme's queue.

   Usage: bb work:theme dogfood-resilience"
  [& args]
  (let [[theme-kw] args
        theme-id  (keyword (or theme-kw "--help"))]
    (if (= :--help theme-id)
      (do
        (println "Usage: bb work:theme <theme-id>")
        (println)
        (println "Available themes:")
        (doseq [[id t] (sort-by key (read-themes))]
          (println (str "  " (name id) " — " (:theme/title t)))))
      (let [all (read-specs)
            themes (read-themes)
            specs (filter #(= theme-id (:theme (priority-of %))) all)]
        (if (empty? specs)
          (println (str "No specs found in theme " theme-id))
          (do
            (println (md-theme-section theme-id (get themes theme-id)
                                       (sort-queue specs) themes))
            (println (str (count specs) " spec(s) in theme " theme-id))))))))
