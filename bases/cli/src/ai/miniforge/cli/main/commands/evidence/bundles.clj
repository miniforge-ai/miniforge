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
  "Discover evidence from providers or disk and derive verified presentation fields."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.evidence.validation :as validation]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers
(defn ^{:stratum 0} evidence-dir []
  (str (app-config/home-dir) "/evidence"))

(defn- ^{:stratum 0} load-with-exception-handling
  "Load an evidence bundle from an EDN file. Returns nil on failure."
  [file]
  (try+
    (when (str/ends-with? (.getName file) ".edn")
      (evidence/read-bundle-edn file))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _ nil)))

(defn- ^{:stratum 0} artifact-entry [artifact]
  (let [type (get artifact :artifact/type (messages/t :evidence/unknown-value))
        id (get artifact :artifact/id "")]
    {:type type :id id}))

(def ^{:stratum 0} ^:private artifact-display-limit
  "Maximum artifact entries in evidence details." 10)

(defn ^{:stratum 0} canonical-status [bundle]
  (messages/t (if (true? (get-in bundle [:evidence/outcome :outcome/success]))
                :status/value-completed :status/value-failed)))

(defn ^{:stratum 0} legacy-field-or
  "Nil/false legacy presentation fields do not override canonical data."
  [bundle field fallback]
  (if-let [value (get bundle field)] value fallback))

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
    (nil? value) (messages/t :evidence/unknown-value)
    :else (str value)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} display-bundle! [diagnostic-id bundle]
  (when (validation/accepted? diagnostic-id bundle)
    (let [id (display/style (str (:evidence-bundle/id bundle)) :bold true)
          workflow-id (:evidence-bundle/workflow-id bundle)
          status (canonical-status bundle)]
      (println (messages/t :evidence/bundle-entry
                          {:id id :workflow-id workflow-id :status status})))))

;; Display helpers
(defn ^{:stratum 1} bundle-detail-spec []
  (let [missing {:default (messages/t :evidence/missing-value)}
        unknown {:default (messages/t :evidence/unknown-value)}]
    {:header   :evidence/show-header
     :fields   [[:bundle/workflow-id :evidence/show-workflow missing]
                [:bundle/status      :evidence/show-status unknown]
                [:bundle/created-at  :evidence/show-created missing]
                [:bundle/failure-attribution :evidence/show-failure-attribution missing]
                [:bundle/dependency-issues :evidence/show-dependency-issues {:default 0}]]
     :sections [{:key :bundle/artifacts :header :evidence/show-artifacts
                 :entry :evidence/show-artifact-entry :max artifact-display-limit
                 :entry-fn artifact-entry}
                {:key :bundle/phases :header :evidence/show-phases}]}))

(defn ^{:stratum 1} load-bundle-from-file [file]
  (load-with-exception-handling file))

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
    (let [source (some failure-attribution [:failure/source :dependency/source])
          vendor (some failure-attribution [:failure/vendor :dependency/vendor :dependency/id])
          failure-class (some failure-attribution [:dependency/class :failure/class])]
      (str (label source) " / " (label vendor) " / " (label failure-class)))))

(defn ^{:stratum 1} canonical-phase-names
  [bundle]
  (->> phase-evidence-keys
       (filter #(contains? bundle %))
       (mapv (comp keyword name))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} display-component-bundles [bundles]
  (if (seq bundles)
    (doseq [bundle bundles]
      (display-bundle! (str (or (:evidence-bundle/id bundle) (:bundle/id bundle))) bundle))
    (println (messages/t :evidence/none))))

(defn ^{:stratum 2} display-filesystem-bundles []
  (let [files (scan-evidence-dir)]
    (if (seq files)
      (doseq [file files]
        (display-bundle! (.getName file) (load-bundle-from-file file)))
      (println (messages/t :evidence/none)))))

(defn ^{:stratum 2} load-bundle-for-show
  "Load a bundle; nil means absent, a sentinel means present but unreadable."
  [id]
  (or (shared/call-optional-provider 'ai.miniforge.evidence-bundle.interface/get-bundle id)
      (let [f (io/file (str (evidence-dir) "/" id ".edn"))]
        (when (.exists f) (or (load-bundle-from-file f) ::unreadable-bundle)))))

(comment
  (scan-evidence-dir))
