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

(ns ai.miniforge.bb-adapter-thesium-risk.core
  "Thesium-risk publish adapter.

   Layer 0: pure — config resolution, date/key helpers.
   Layer 1: orchestration steps — produce-snapshot!, run-etl!, upload-pair!.
   Layer 2: top-level compositions — daily!, weekly!."
  (:require [ai.miniforge.bb-data-plane-http.interface :as dp]
            [ai.miniforge.bb-out.interface :as out]
            [ai.miniforge.bb-paths.interface :as paths]
            [ai.miniforge.bb-proc.interface :as proc]
            [ai.miniforge.bb-r2.interface :as r2]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults + pure helpers.

(def defaults
  "Risk-dashboard conventions baked into the adapter. Consumers may
   override individual keys via the cfg map passed in."
  {:data-plane   {:binary        "thesium-data-plane/target/release/thesium-data-plane"
                  :manifest      "thesium-data-plane/Cargo.toml"
                  :base-url      "http://127.0.0.1:8787"
                  :base-url-env  "THESIUM_DATA_PLANE_BASE_URL"
                  :state-dir-env "THESIUM_DATA_PLANE_STATE_DIR"}
   :miniforge-dir      "../miniforge"
   :research-lens-dir  "packs/research-lens"
   :daily-output       "dist/daily"
   :weekly-output      "dist/weekly"
   :snapshot-filename  "dashboard_snapshot.json"
   :catalog-filename   "research_catalog.json"
   :daily-canonical-snapshot "daily/dashboard_snapshot.json"
   :daily-canonical-catalog  "daily/research_catalog.json"
   :daily-cache-control      "public, max-age=3600"
   :weekly-canonical-snapshot "weekly/dashboard_snapshot.json"
   :weekly-cache-control      "public, max-age=86400"
   :fred-api-key-env  "FRED_API_KEY"
   :r2-bucket-env     "R2_BUCKET"
   :r2-endpoint-env   "R2_ENDPOINT"})

(defn resolve-cfg
  "Merge defaults with `cfg`, resolving env-var-backed values."
  [cfg]
  (let [m (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                      defaults cfg)
        get-env #(System/getenv %)]
    (assoc m
           :root          (or (:root m) (paths/repo-root))
           :fred-api-key  (get-env (:fred-api-key-env m))
           :r2-bucket     (get-env (:r2-bucket-env m))
           :r2-endpoint   (get-env (:r2-endpoint-env m)))))

(defn today-iso
  "UTC today, ISO-8601."
  []
  (.format (java.time.LocalDate/now java.time.ZoneOffset/UTC)
           java.time.format.DateTimeFormatter/ISO_LOCAL_DATE))

(defn- under-root
  [root p]
  (if (str/starts-with? p "/") p (str root "/" p)))

(defn- daily-archive-snapshot-key  [date] (str "daily/archive/"  date "-snapshot.json"))
(defn- daily-archive-catalog-key   [date] (str "daily/archive/"  date "-catalog.json"))
(defn- weekly-archive-snapshot-key [date] (str "weekly/archive/" date ".json"))

(defn validate!
  [{:keys [fred-api-key]}]
  (when (str/blank? fred-api-key)
    (throw (ex-info "FRED_API_KEY is required (set via :fred-api-key-env)"
                    {:missing :fred-api-key}))))

;------------------------------------------------------------------------------ Layer 1
;; Orchestration steps.

(defn- snapshot-file [{:keys [root] :as cfg} output-dir]
  (str (under-root root output-dir) "/" (:snapshot-filename cfg)))

(defn- catalog-file [{:keys [root] :as cfg} output-dir]
  (str (under-root root output-dir) "/" (:catalog-filename cfg)))

(defn produce-snapshot!
  "Build + start the data plane, hit /v1/admin/refresh {:scenario 'live'},
   download /v1/snapshots/latest, stop. Writes `out-file`. Returns
   snapshot id."
  [{:keys [root data-plane fred-api-key] :as cfg} out-file]
  (let [dp-cfg       (assoc data-plane :root root)
        base-url     (dp/resolve-base-url dp-cfg)
        _            (dp/build! dp-cfg)
        handle       (dp/start! dp-cfg {:env {"FRED_API_KEY" fred-api-key}})]
    (try
      (dp/wait-ready! (str base-url "/v1/snapshots/latest")
                      {:proc-handle handle})
      (out/section "Triggering live FRED refresh")
      (dp/http-post-json (str base-url "/v1/admin/refresh")
                         {:scenario "live"} {})
      (out/section "Downloading snapshot")
      (fs/create-dirs (fs/parent out-file))
      (let [body (dp/http-get-body (str base-url "/v1/snapshots/latest") {})
            _    (spit out-file body)
            id   (-> body (json/parse-string true) :snapshot_id)]
        (out/step (str "snapshot id: " id))
        id)
      (finally
        (out/section "Stopping data plane")
        (dp/destroy! handle)))))

(defn run-etl!
  "Run the research-lens ETL via a sibling miniforge checkout. Returns
   `{:success? bool :catalog path-or-nil}`. Skipped (not fatal) when
   the miniforge dir is missing."
  [{:keys [root miniforge-dir research-lens-dir] :as cfg} output-dir]
  (out/section "Running research-lens ETL")
  (let [mf-abs (under-root root miniforge-dir)]
    (if-not (fs/exists? mf-abs)
      (do (out/warn (str "miniforge not found at " mf-abs ", skipping"))
          {:success? false :catalog nil :error "miniforge not found"})
      (let [pack-dir     (under-root root research-lens-dir)
            pack-src     (str pack-dir "/src")
            pipeline-def (str pack-dir "/pipelines/research-lens-etl.edn")
            env-file     (str pack-dir "/envs/gha.edn")
            out-file     (catalog-file cfg output-dir)
            deps-override (str "{:deps {local/pack {:local/root \"" pack-src "\"}}}")
            ;; The CLI lives in the consuming repo (Thesium), not in
            ;; miniforge — miniforge provides the pipeline-runner,
            ;; connector-*, and schema primitives, while product-
            ;; specific glue (env conventions, ${VAR} interpolation,
            ;; transform registration) lives with the pack. The
            ;; :local/root above puts the pack's src on the classpath
            ;; so `ai.thesium.etl.cli` is resolvable here.
            result (proc/sh {:dir mf-abs
                             :out (str (under-root root output-dir) "/etl.log")
                             :err :inherit}
                            "clojure" "-Sdeps" deps-override
                            "-M:dev" "-m" "ai.thesium.etl.cli"
                            "run" pipeline-def "--env" env-file
                            "--output" out-file)]
        (if (and (zero? (:exit result)) (fs/exists? out-file))
          (do (out/ok "research catalog produced")
              {:success? true :catalog out-file})
          (do (out/warn "research catalog not produced")
              {:success? false :catalog nil :error "ETL failed"}))))))

(defn upload-pair!
  "Upload `src` to canonical + archive keys on R2. Canonical failure
   propagates; archive failure is logged but does not abort."
  [{:keys [r2-bucket r2-endpoint]} src canonical-key archive-key cache-control]
  (r2/upload! r2-bucket src canonical-key
              {:endpoint r2-endpoint :cache-control cache-control})
  (try
    (r2/upload! r2-bucket src archive-key {:endpoint r2-endpoint})
    (catch Exception e
      (out/warn (str "archive upload failed for " archive-key ": " (ex-message e))))))

;------------------------------------------------------------------------------ Layer 2
;; Top-level compositions.

(defn- upload-daily!
  [{:keys [r2-bucket] :as cfg} snapshot-path catalog-result date]
  (out/section (str "Uploading to R2 (bucket: " r2-bucket ")"))
  (upload-pair! cfg snapshot-path
                (:daily-canonical-snapshot cfg)
                (daily-archive-snapshot-key date)
                (:daily-cache-control cfg))
  (out/ok "snapshot uploaded")
  (when (:success? catalog-result)
    (upload-pair! cfg (:catalog catalog-result)
                  (:daily-canonical-catalog cfg)
                  (daily-archive-catalog-key date)
                  (:daily-cache-control cfg))
    (out/ok "catalog uploaded")))

(defn- upload-weekly!
  [{:keys [r2-bucket] :as cfg} snapshot-path date]
  (out/section (str "Uploading to R2 (bucket: " r2-bucket ")"))
  (upload-pair! cfg snapshot-path
                (:weekly-canonical-snapshot cfg)
                (weekly-archive-snapshot-key date)
                (:weekly-cache-control cfg))
  (out/ok "snapshot uploaded"))

(defn- skip-upload!
  [snapshot-path catalog-result]
  (out/section "R2 not configured, skipping upload")
  (out/step (str "snapshot saved to " snapshot-path))
  (when (:success? catalog-result)
    (out/step (str "catalog saved to " (:catalog catalog-result)))))

(defn daily!
  [cfg-in]
  (let [cfg           (resolve-cfg cfg-in)
        _             (validate! cfg)
        output-dir    (:daily-output cfg)
        abs-output    (under-root (:root cfg) output-dir)
        snap-path     (snapshot-file cfg output-dir)]
    (fs/create-dirs abs-output)
    (produce-snapshot! cfg snap-path)
    (let [etl (run-etl! cfg output-dir)]
      (if (:r2-bucket cfg)
        (upload-daily! cfg snap-path etl (today-iso))
        (skip-upload! snap-path etl))
      (out/section "Done")
      {:success? true
       :snapshot snap-path
       :catalog  (when (:success? etl) (:catalog etl))})))

(defn weekly!
  [cfg-in]
  (let [cfg         (resolve-cfg cfg-in)
        _           (validate! cfg)
        output-dir  (:weekly-output cfg)
        abs-output  (under-root (:root cfg) output-dir)
        snap-path   (snapshot-file cfg output-dir)]
    (fs/create-dirs abs-output)
    (produce-snapshot! cfg snap-path)
    (if (:r2-bucket cfg)
      (upload-weekly! cfg snap-path (today-iso))
      (skip-upload! snap-path {:success? false}))
    (out/section "Done")
    {:success? true :snapshot snap-path}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (resolve-cfg {})
  (daily-archive-snapshot-key "2026-04-19")
  (daily! {})

  :leave-this-here)
