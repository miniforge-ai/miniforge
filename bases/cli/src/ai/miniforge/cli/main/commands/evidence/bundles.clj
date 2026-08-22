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
(ns ai.miniforge.cli.main.commands.evidence.bundles
  "Evidence bundle discovery, loading, and field-derivation helpers.
   Split out of `ai.miniforge.cli.main.commands.evidence` (rule 210:
   the combined namespace measured 5 real layers, max 3) — the command
   entry points and detail-view rendering stay in the parent
   namespace; locating/loading bundle files (filesystem scan and the
   optional component provider) and deriving their normalized/summary
   fields live here."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers
(defn ^{:stratum 0} evidence-dir []
  (str (app-config/home-dir) "/evidence"))

(defn ^{:stratum 0} load-bundle-from-file
  "Load an evidence bundle from an EDN file. Returns nil on failure."
  [file]
  (try
    (when (str/ends-with? (.getName file) ".edn")
      (edn/read-string (slurp file)))
    (catch Exception _ nil)))

;; Display helpers
(def ^{:stratum 0} bundle-detail-spec
  {:header   :evidence/show-header
   :fields   [[:bundle/workflow-id :evidence/show-workflow {:default "—"}]
              [:bundle/status      :evidence/show-status   {:default "unknown"}]
              [:bundle/created-at  :evidence/show-created  {:default "—"}]
              [:bundle/failure-attribution :evidence/show-failure-attribution {:default "—"}]
              [:bundle/dependency-issues :evidence/show-dependency-issues {:default 0}]]
   :sections [{:key :bundle/artifacts :header :evidence/show-artifacts
               :entry :evidence/show-artifact-entry :max 10
               :entry-fn (fn [a] {:type (get a :artifact/type "unknown")
                                   :id   (get a :artifact/id "")})}
              {:key :bundle/phases :header :evidence/show-phases}]})

(def ^{:stratum 0} ^:private phase-evidence-keys
  [:evidence/plan
   :evidence/design
   :evidence/implement
   :evidence/verify
   :evidence/review
   :evidence/release
   :evidence/observe])

(defn- ^{:stratum 0} active-dependency?
  [dependency]
  (contains? #{:degraded :unavailable :misconfigured :operator-action-required}
             (:dependency/status dependency)))

(defn- ^{:stratum 0} label
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) "unknown"
    :else (str value)))

;; Command implementations
(defn ^{:stratum 0} display-component-bundles
  "Render bundles returned from the evidence-bundle component interface."
  [bundles]
  (if (seq bundles)
    (doseq [bundle bundles]
      (println (messages/t :evidence/bundle-entry
                          {:id          (display/style (get bundle :bundle/id "unknown") :foreground :bold)
                           :workflow-id (get bundle :bundle/workflow-id "—")
                           :status      (get bundle :bundle/status "unknown")})))
    (println (messages/t :evidence/none))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} scan-evidence-dir []
  (let [dir (io/file (evidence-dir))]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           vec))))

(defn ^{:stratum 1} dependency-issue-count
  [dependency-health]
  (->> dependency-health
       vals
       (filter active-dependency?)
       count))

(defn ^{:stratum 1} failure-attribution-summary
  [failure-attribution]
  (when (seq failure-attribution)
    (let [source (or (:failure/source failure-attribution)
                     (:dependency/source failure-attribution)
                     :unknown)
          vendor (or (:failure/vendor failure-attribution)
                     (:dependency/vendor failure-attribution)
                     (:dependency/id failure-attribution))
          failure-class (or (:dependency/class failure-attribution)
                            (:failure/class failure-attribution)
                            :unknown)]
      (str (label source) " / " (label vendor) " / " (label failure-class)))))

(defn ^{:stratum 1} canonical-phase-names
  [bundle]
  (->> phase-evidence-keys
       (filter #(contains? bundle %))
       (mapv (comp keyword name))))

(defn ^{:stratum 1} load-bundle-for-show
  "Load a bundle from the component interface or the filesystem."
  [id]
  (or (shared/call-optional-provider 'ai.miniforge.evidence-bundle.interface/get-bundle id)
      (let [f (io/file (str (evidence-dir) "/" id ".edn"))]
        (when (.exists f) (load-bundle-from-file f)))))

(defn ^{:stratum 1} export-bundle-fallback
  "Copy the raw EDN bundle file as-is when the export component is unavailable."
  [id fmt]
  (let [src (io/file (str (evidence-dir) "/" id ".edn"))]
    (if (.exists src)
      (let [dest (str (evidence-dir) "/" id "-export." fmt)]
        (fs/copy (str src) dest {:replace-existing true})
        (display/print-success (messages/t :evidence/export-raw {:path dest})))
      (do (display/print-error (messages/t :evidence/export-not-found {:id id}))
          (shared/exit! 1)))))
