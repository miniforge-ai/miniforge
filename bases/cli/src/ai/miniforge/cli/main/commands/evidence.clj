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

(ns ai.miniforge.cli.main.commands.evidence
  "Evidence bundle commands: show, export, list.

   Delegates to ai.miniforge.evidence-bundle.interface when available.
   Falls back to filesystem scanning of ~/.miniforge/evidence/."
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

(defn- evidence-dir []
  (str (app-config/home-dir) "/evidence"))

(defn- scan-evidence-dir []
  (let [dir (io/file (evidence-dir))]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile %))
           (sort-by #(.lastModified %) >)
           vec))))

(defn load-bundle-from-file
  "Load an evidence bundle from an EDN file. Returns nil on failure."
  [file]
  (try
    (when (str/ends-with? (.getName file) ".edn")
      (edn/read-string (slurp file)))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Display helpers

(def ^:private bundle-detail-spec
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

(def ^:private phase-evidence-keys
  [:evidence/plan
   :evidence/design
   :evidence/implement
   :evidence/verify
   :evidence/review
   :evidence/release
   :evidence/observe])

(defn- active-dependency?
  [dependency]
  (contains? #{:degraded :unavailable :misconfigured :operator-action-required}
             (:dependency/status dependency)))

(defn- label
  [value]
  (cond
    (keyword? value) (name value)
    (string? value) value
    (nil? value) "unknown"
    :else (str value)))

(defn- dependency-issue-count
  [dependency-health]
  (->> dependency-health
       vals
       (filter active-dependency?)
       count))

(defn- failure-attribution-summary
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

(defn- canonical-phase-names
  [bundle]
  (->> phase-evidence-keys
       (filter #(contains? bundle %))
       (mapv (comp keyword name))))

(defn- normalize-bundle-detail
  [bundle]
  (let [dependency-health (or (:evidence/dependency-health bundle) {})
        artifacts (or (:bundle/artifacts bundle) [])
        phases (or (:bundle/phases bundle)
                   (canonical-phase-names bundle))
        status (or (:bundle/status bundle)
                   (if (true? (get-in bundle [:evidence/outcome :outcome/success]))
                     "completed"
                     "failed"))]
    {:bundle/workflow-id (or (:bundle/workflow-id bundle)
                             (:evidence-bundle/workflow-id bundle))
     :bundle/status status
     :bundle/created-at (or (:bundle/created-at bundle)
                            (:evidence-bundle/created-at bundle))
     :bundle/artifacts artifacts
     :bundle/phases phases
     :bundle/dependency-issues (dependency-issue-count dependency-health)
     :bundle/failure-attribution (failure-attribution-summary
                                  (:evidence/failure-attribution bundle))}))

(defn- display-bundle-detail
  "Render the detail view for a single evidence bundle."
  [id bundle]
  (display/render-detail (assoc bundle-detail-spec :header-params {:id id})
                         (normalize-bundle-detail bundle)))

(defn- load-bundle-for-show
  "Load a bundle from the component interface or the filesystem."
  [id]
  (or (shared/try-resolve-fn 'ai.miniforge.evidence-bundle.interface/get-bundle id)
      (let [f (io/file (str (evidence-dir) "/" id ".edn"))]
        (when (.exists f) (load-bundle-from-file f)))))

;------------------------------------------------------------------------------ Layer 2
;; Command implementations

(defn- display-component-bundles
  "Render bundles returned from the evidence-bundle component interface."
  [bundles]
  (if (seq bundles)
    (doseq [bundle bundles]
      (println (messages/t :evidence/bundle-entry
                          {:id          (display/style (get bundle :bundle/id "unknown") :foreground :bold)
                           :workflow-id (get bundle :bundle/workflow-id "—")
                           :status      (get bundle :bundle/status "unknown")})))
    (println (messages/t :evidence/none))))

(defn- display-filesystem-bundles
  "Render bundles discovered via filesystem scan."
  []
  (let [files (scan-evidence-dir)]
    (if (seq files)
      (doseq [f files]
        (let [bundle (load-bundle-from-file f)
              id     (if bundle
                       (or (some-> (:bundle/id bundle) str) (.getName f))
                       (.getName f))]
          (println (str "  " (display/style id :foreground :bold)
                        (when (and bundle (:bundle/workflow-id bundle))
                          (str "  wf:" (:bundle/workflow-id bundle)))
                        (when (and bundle (:bundle/status bundle))
                          (str "  (" (:bundle/status bundle) ")"))))))
      (do
        (println (messages/t :evidence/none))
        (println (messages/t :evidence/evidence-dir {:dir (evidence-dir)}))))))

(defn evidence-list-cmd
  "List all available evidence bundles.

   Shows bundles from the evidence-bundle component if available,
   otherwise scans ~/.miniforge/evidence/."
  [_opts]
  (println)
  (println (display/style (messages/t :evidence/header) :foreground :cyan :bold true))
  (println)
  (let [component-result (shared/try-resolve-fn 'ai.miniforge.evidence-bundle.interface/list-bundles)]
    (if component-result
      (display-component-bundles component-result)
      (display-filesystem-bundles)))
  (println))

(defn evidence-show-cmd
  "Show the contents of an evidence bundle by ID."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (shared/usage-error! :evidence/show-usage "evidence show <id>")
      (let [bundle (load-bundle-for-show id)]
        (if-not bundle
          (do (display/print-error
               (messages/t :evidence/not-found
                          {:id id
                           :command (app-config/command-string "evidence list")}))
              (shared/exit! 1))
          (display-bundle-detail id bundle))))))

(defn- export-bundle-fallback
  "Copy the raw EDN bundle file as-is when the export component is unavailable."
  [id fmt]
  (let [src (io/file (str (evidence-dir) "/" id ".edn"))]
    (if (.exists src)
      (let [dest (str (evidence-dir) "/" id "-export." fmt)]
        (fs/copy (str src) dest {:replace-existing true})
        (display/print-success (messages/t :evidence/export-raw {:path dest})))
      (do (display/print-error (messages/t :evidence/export-not-found {:id id}))
          (shared/exit! 1)))))

(defn evidence-export-cmd
  "Export an evidence bundle to a file in the requested format.

   Supported formats: edn (default), json, html."
  [opts]
  (let [{:keys [id format]} opts
        fmt (or format "edn")]
    (if-not id
      (shared/usage-error! :evidence/export-usage "evidence export <id> <format>")
      (let [result (shared/try-resolve-fn
                    'ai.miniforge.evidence-bundle.interface/export-bundle id fmt)]
        (if result
          (do
            (display/print-success (messages/t :evidence/export-success {:id id}))
            (println (messages/t :evidence/export-format {:format fmt}))
            (when-let [path (:path result)]
              (println (messages/t :evidence/export-path {:path path}))))
          (export-bundle-fallback id fmt))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (evidence-list-cmd {})
  (evidence-show-cmd {:id "some-bundle-id"})
  (evidence-export-cmd {:id "some-bundle-id" :format "json"})
  :end)
