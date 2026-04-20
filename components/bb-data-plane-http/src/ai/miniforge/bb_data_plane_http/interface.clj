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

(ns ai.miniforge.bb-data-plane-http.interface
  "Primitives for a Rust-backed Thesium-style data plane: cargo
   build/start/wait-ready/destroy lifecycle + generic HTTP helpers.

   The domain-specific wrappers (`refresh!`, `snapshot-latest`,
   `inject!`, etc.) stay in each consumer's adapter component — this
   primitive covers lifecycle + unshaped HTTP. Pass-through to `core`."
  (:require [ai.miniforge.bb-data-plane-http.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Config resolution

(defn resolve-base-url
  "Return the base URL for the data plane. Honors the env var named in
   `:base-url-env` (default `THESIUM_DATA_PLANE_BASE_URL`), falling back
   to `:base-url` (default `http://127.0.0.1:8787`)."
  [cfg]
  (core/resolve-base-url cfg))

(defn binary-path
  "Return the absolute path of the data-plane binary for `cfg`.
   `cfg` = {:binary relative-path :root absolute-path-or-nil}."
  [cfg]
  (core/binary-path cfg))

(defn manifest-path
  "Return the absolute path of the Cargo manifest for `cfg`.
   `cfg` = {:manifest relative-path :root absolute-path-or-nil}."
  [cfg]
  (core/manifest-path cfg))

;------------------------------------------------------------------------------ Layer 1
;; Process lifecycle

(defn build!
  "Cargo-build the data plane in release mode. Returns the binary path.
   `cfg` per `binary-path`/`manifest-path`. `opts` may include
   `:run-fn`, `:quiet?`."
  ([cfg]      (core/build! cfg {}))
  ([cfg opts] (core/build! cfg opts)))

(defn start!
  "Spawn the data plane in the background. Returns the process handle.
   `opts`: `:env` (extra env map), `:state-dir`, `:state-dir-env`,
   `:log-path`, `:bg-fn`."
  ([cfg]      (core/start! cfg {}))
  ([cfg opts] (core/start! cfg opts)))

(defn wait-ready!
  "Poll `health-url` until it responds (< 500) or attempts exhausted.
   Throws ex-info on exhaustion or early process death.
   `opts`: `:max-attempts` (60), `:http-fn`, `:sleep-fn` (250 ms),
   `:proc-handle`."
  ([health-url]      (core/wait-ready! health-url {}))
  ([health-url opts] (core/wait-ready! health-url opts)))

(defn destroy!
  "Tear down a running process handle (pass-through to bb-proc)."
  [proc]
  (core/destroy! proc))

;------------------------------------------------------------------------------ Layer 2
;; Generic HTTP helpers

(defn http-get-body
  "GET `url`; return raw body string (or nil on error). `opts`: `:http-fn`."
  ([url]      (core/http-get-body url {}))
  ([url opts] (core/http-get-body url opts)))

(defn http-get-json
  "GET `url`; parse JSON body into keywordized map. Throws ex-info on
   non-200. `opts`: `:http-fn`."
  ([url]      (core/http-get-json url {}))
  ([url opts] (core/http-get-json url opts)))

(defn http-post-json
  "POST `url` with `body-map` as JSON. Parse response JSON.
   Throws ex-info on non-200. `opts`: `:http-fn`."
  ([url body-map]      (core/http-post-json url body-map {}))
  ([url body-map opts] (core/http-post-json url body-map opts)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (resolve-base-url {:base-url "http://localhost:8787"})
  (binary-path {:root "/tmp/repo" :binary "bin/dp"})

  :leave-this-here)
