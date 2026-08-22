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
   Falls back to filesystem scanning of ~/.miniforge/evidence/.

   Bundle discovery/loading and field-derivation helpers live in the
   sibling `ai.miniforge.cli.main.commands.evidence.bundles` namespace
   (rule 210: the combined namespace measured 5 real layers, max 3);
   this namespace keeps the three command entry points and the
   detail-view rendering they share."
  (:require
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.evidence.bundles :as bundles]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} normalize-bundle-detail
  [bundle]
  (let [dependency-health (or (:evidence/dependency-health bundle) {})
        artifacts (or (:bundle/artifacts bundle) [])
        phases (or (:bundle/phases bundle)
                   (bundles/canonical-phase-names bundle))
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
     :bundle/dependency-issues (bundles/dependency-issue-count dependency-health)
     :bundle/failure-attribution (bundles/failure-attribution-summary
                                  (:evidence/failure-attribution bundle))}))

(defn- ^{:stratum 0} display-filesystem-bundles
  "Render bundles discovered via filesystem scan."
  []
  (let [files (bundles/scan-evidence-dir)]
    (if (seq files)
      (doseq [f files]
        (let [bundle (bundles/load-bundle-from-file f)
              id     (if bundle
                       (or (some-> (:bundle/id bundle) str) (.getName f))
                       (.getName f))]
          (println (messages/t :evidence/bundle-entry
                                {:id          (display/style id :bold true)
                                :workflow-id (if (and bundle (:bundle/workflow-id bundle))
                                               (str (:bundle/workflow-id bundle))
                                               "—")
                                :status      (if (and bundle (:bundle/status bundle))
                                               (str (:bundle/status bundle))
                                               "—")}))))
      (do
        (println (messages/t :evidence/none))
        (println (messages/t :evidence/evidence-dir {:dir (bundles/evidence-dir)}))))))

(defn ^{:stratum 0} evidence-export-cmd
  "Export an evidence bundle to a file in the requested format.

   Supported formats: edn (default), json, html."
  [opts]
  (let [{:keys [id format]} opts
        fmt (or format "edn")]
    (if-not id
      (shared/usage-error! :evidence/export-usage "evidence export <id> <format>")
      (let [result (shared/call-optional-provider
                    'ai.miniforge.evidence-bundle.interface/export-bundle id fmt)]
        (if result
          (do
            (display/print-success (messages/t :evidence/export-success {:id id}))
            (println (messages/t :evidence/export-format {:format fmt}))
            (when-let [path (:path result)]
              (println (messages/t :evidence/export-path {:path path}))))
          (bundles/export-bundle-fallback id fmt))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} display-bundle-detail
  "Render the detail view for a single evidence bundle."
  [id bundle]
  (display/render-detail (assoc bundles/bundle-detail-spec :header-params {:id id})
                         (normalize-bundle-detail bundle)))

(defn ^{:stratum 1} evidence-list-cmd
  "List all available evidence bundles.

   Shows bundles from the evidence-bundle component if available,
   otherwise scans ~/.miniforge/evidence/."
  [_opts]
  (println)
  (println (display/style (messages/t :evidence/header) :foreground :cyan :bold true))
  (println)
  (let [component-result (shared/call-optional-provider
                          'ai.miniforge.evidence-bundle.interface/list-bundles)]
    (if component-result
      (bundles/display-component-bundles component-result)
      (display-filesystem-bundles)))
  (println))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evidence-show-cmd
  "Show the contents of an evidence bundle by ID."
  [opts]
  (let [{:keys [id]} opts]
    (if-not id
      (shared/usage-error! :evidence/show-usage "evidence show <id>")
      (let [bundle (bundles/load-bundle-for-show id)]
        (if-not bundle
          (do (display/print-error
               (messages/t :evidence/not-found
                          {:id id
                           :command (app-config/command-string "evidence list")}))
              (shared/exit! 1))
          (display-bundle-detail id bundle))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (evidence-list-cmd {})
  (evidence-show-cmd {:id "some-bundle-id"})
  (evidence-export-cmd {:id "some-bundle-id" :format "json"})
  :end)
