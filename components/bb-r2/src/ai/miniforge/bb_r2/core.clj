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

(ns ai.miniforge.bb-r2.core
  "R2 primitives implementation.

   Layer 0: pure command-vector builders — no subprocess, testable.
   Layer 1: side-effecting wrappers that invoke the vectors via bb-proc."
  (:require [ai.miniforge.bb-proc.interface :as proc]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pure: classify wrangler result + build aws-cp args.

(defn classify-wrangler-result
  "Given a process result `{:exit :err}`, return `:ok` / `:missing` /
   `:error`. `:missing` means the key wasn't there (404-equivalent)."
  [{:keys [exit err]}]
  (cond
    (zero? exit)                              :ok
    (str/includes? (or err "") "The specified key does not exist") :missing
    :else                                     :error))

(defn build-upload-cmd
  "Construct the `aws s3 cp` argv for uploading `src` to `bucket/key`.
   `opts` = {:endpoint :content-type :cache-control}. Returns a vector."
  [bucket src key {:keys [endpoint content-type cache-control]
                   :or   {content-type "application/json"}}]
  (let [dest (str "s3://" bucket "/" key)]
    (cond-> ["aws" "s3" "cp" src dest "--content-type" content-type]
      endpoint      (into ["--endpoint-url" endpoint])
      cache-control (into ["--cache-control" cache-control]))))

;------------------------------------------------------------------------------ Layer 1
;; Side effects.

(defn pull!
  [key dest {:keys [worker-dir bucket sh-fn]
             :or   {sh-fn proc/sh}}]
  (when-not worker-dir
    (throw (ex-info "bb-r2/pull! requires :worker-dir" {:opts :worker-dir})))
  (when-not bucket
    (throw (ex-info "bb-r2/pull! requires :bucket" {:opts :bucket})))
  (let [result (sh-fn {:dir worker-dir :out :string :err :string}
                      "npx" "wrangler" "r2" "object" "get"
                      (str bucket "/" key)
                      (str "--file=" dest)
                      "--remote")
        kind   (classify-wrangler-result result)]
    (case kind
      :ok      :ok
      :missing :missing
      (throw (ex-info (str "wrangler r2 get failed for " key)
                      {:exit (:exit result) :err (:err result)})))))

(defn upload!
  [bucket src key {:keys [run-fn] :or {run-fn proc/run!} :as opts}]
  (let [cmd (build-upload-cmd bucket src key opts)]
    (apply run-fn cmd)
    (str "s3://" bucket "/" key)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (classify-wrangler-result {:exit 0 :err ""})
  (build-upload-cmd "bucket" "/tmp/x.json" "a/b.json"
                    {:endpoint "https://r2"
                     :cache-control "public, max-age=3600"})

  :leave-this-here)
