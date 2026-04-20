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

(ns ai.miniforge.bb-generate-icon.core
  "`.icns` generator.

   Layer 0: pure path resolution (`plan`) — config map → abs-patholute-path
   plan, no filesystem access.
   Layer 1: side-effecting steps — placeholder synth, iconset build,
   iconutil invocation.
   Layer 2: `run!` orchestrator."
  (:require [ai.miniforge.bb-out.interface :as out]
            [ai.miniforge.bb-paths.interface :as paths]
            [ai.miniforge.bb-proc.interface :as proc]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0
;; Pure plan — resolves config to abs-patholute paths.

(defn- abs-path
  [root p]
  (if (fs/absolute? p) (str p) (str root "/" p)))

(defn plan
  "Resolve `cfg` against `:root` (defaults to current repo root). Returns
   an abs-patholute-path plan."
  [cfg]
  (let [root       (or (:root cfg) (paths/repo-root))
        output-dir (abs-path root (:output-dir cfg))]
    {:root            root
     :output-dir      output-dir
     :icns-path       (str output-dir "/" (:icns-name cfg))
     :iconset-dir     (str output-dir "/" (:iconset-name cfg))
     :sizes           (:sizes cfg)
     :default-source  (abs-path root (:default-source cfg))
     :placeholder     (when-let [ph (:placeholder cfg)]
                        {:path       (abs-path root (:path ph))
                         :swift-file (abs-path root (:swift-file ph))})}))

;------------------------------------------------------------------------------ Layer 1
;; Side-effecting steps.

(defn- generate-placeholder!
  [{:keys [swift-file path]}]
  (let [swift-src (slurp swift-file)]
    (proc/run! "swift" "-e" swift-src path)))

(defn- generate-iconset!
  [source iconset-dir sizes]
  (fs/delete-tree iconset-dir)
  (fs/create-dirs iconset-dir)
  (doseq [size sizes
          :let [double-size (* 2 size)]]
    (proc/run! "sips" "-z" (str size) (str size) source
               "--out" (str iconset-dir "/icon_" size "x" size ".png"))
    (proc/run! "sips" "-z" (str double-size) (str double-size) source
               "--out" (str iconset-dir "/icon_" size "x" size "@2x.png"))))

(defn- resolve-source!
  "Return a usable source PNG. Synthesize a placeholder if the default
   is missing and a placeholder is configured."
  [p override]
  (let [requested (or override (:default-source p))]
    (cond
      (fs/exists? requested)
      requested

      (:placeholder p)
      (do (out/section (str "No source icon found at " requested))
          (out/step "Generating placeholder icon...")
          (fs/create-dirs (:output-dir p))
          (generate-placeholder! (:placeholder p))
          (:path (:placeholder p)))

      :else
      (throw (ex-info (str "No source icon at " requested
                           " and no placeholder configured.")
                      {:source requested :cfg p})))))

;------------------------------------------------------------------------------ Layer 2
;; Orchestrator.

(defn run!
  [cfg args]
  (let [p      (plan cfg)
        source (resolve-source! p (first args))]
    (fs/create-dirs (:output-dir p))
    (out/section (str "Creating iconset from " source))
    (generate-iconset! source (:iconset-dir p) (:sizes p))
    (out/section "Generating .icns")
    (proc/run! "iconutil" "-c" "icns" (:iconset-dir p) "-o" (:icns-path p))
    (fs/delete-tree (:iconset-dir p))
    (println (str "ICNS_PATH=" (:icns-path p)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (plan {:root "/tmp/demo"
         :output-dir "dist" :icns-name "AppIcon.icns"
         :iconset-name "AppIcon.iconset" :sizes [16 32]
         :default-source "product/AppIcon.png"})

  :leave-this-here)
