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

(ns ai.miniforge.bb-r2.interface
  "Cloudflare R2 primitives: pull a key via wrangler, upload a file via
   the R2 S3-compatible endpoint. Pass-through to `core`."
  (:require [ai.miniforge.bb-r2.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Public API — pass-through only

(defn pull!
  "Pull `bucket/key` from R2 via `npx wrangler r2 object get`, writing
   to `dest`. Returns `:ok` on success, `:missing` on a 404-ish wrangler
   failure, throws on other errors.

   Required opts: `:worker-dir` (directory where wrangler runs),
   `:bucket`. Optional: `:sh-fn` (defaults to `bb-proc/sh`)."
  [key dest opts]
  (core/pull! key dest opts))

(defn upload!
  "Upload `src` to `s3://bucket/key` via `aws s3 cp` against R2.
   `opts` may include `:endpoint`, `:content-type`, `:cache-control`,
   `:run-fn` (defaults to `bb-proc/run!`). Returns the destination URL."
  [bucket src key opts]
  (core/upload! bucket src key opts))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (pull! "daily/snapshot.json" "/tmp/x.json"
         {:worker-dir "/tmp/worker" :bucket "my-bucket"})
  (upload! "my-bucket" "/tmp/x.json" "daily/snapshot.json"
           {:endpoint "https://acct.r2.cloudflarestorage.com"
            :cache-control "public, max-age=3600"})

  :leave-this-here)
