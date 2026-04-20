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

(ns ai.miniforge.bb-generate-icon.interface
  "Generate a macOS `.icns` from a source PNG via sips + iconutil.
   Pass-through to `core`."
  (:require [ai.miniforge.bb-generate-icon.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn plan
  "Return the fully-resolved plan (absolute paths + sizes) for `cfg`.
   Pure; does not touch the filesystem."
  [cfg]
  (core/plan cfg))

(defn run!
  "Produce an `.icns` from a source PNG. `cfg` shape:
     {:output-dir     \"dist\"
      :icns-name      \"AppIcon.icns\"
      :iconset-name   \"AppIcon.iconset\"
      :sizes          [16 32 128 256 512]
      :default-source \"product/AppIcon.png\"
      :placeholder    {:path \"dist/AppIcon_1024.png\"
                       :swift-file \"dev/generate-placeholder-icon.swift\"}}
   Paths are resolved relative to the repo root. `args` is the bb
   `*command-line-args*` vector; its first element overrides the source
   PNG path."
  ([cfg] (run! cfg nil))
  ([cfg args] (core/run! cfg args)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (plan {:output-dir "dist" :icns-name "AppIcon.icns"
         :iconset-name "AppIcon.iconset" :sizes [16 32]
         :default-source "product/AppIcon.png"})

  :leave-this-here)
