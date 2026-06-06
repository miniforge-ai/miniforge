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

(ns ai.miniforge.connector-http.etag
  "Shared ETag caching for conditional HTTP requests.
   Stores ETags per URL, adds If-None-Match headers to subsequent requests.
   304 Not Modified responses don't count against rate limits.")

;;------------------------------------------------------------------------------ Layer 0
;; Cache

(defonce ^:private cache (atom {}))

(defn get-etag
  "Retrieve cached ETag for a URL."
  [url]
  (get @cache url))

(defn store-etag!
  "Cache an ETag for a URL."
  [url etag]
  (when etag
    (swap! cache assoc url etag)))

(defn clear-cache!
  "Clear the entire ETag cache. Useful for testing."
  []
  (reset! cache {}))

;;------------------------------------------------------------------------------ Layer 1
;; Header helpers

(defn add-etag-header
  "Add If-None-Match header if we have a cached ETag for this URL."
  [headers url]
  (if-let [etag (get-etag url)]
    (assoc headers "If-None-Match" etag)
    headers))

(defn extract-etag
  "Extract the ETag header from response headers."
  [response-headers]
  (get response-headers "etag"))

(defn not-modified?
  "Check if an HTTP status indicates Not Modified."
  [status]
  (= 304 status))

(comment
  ;; (store-etag! "https://api.github.com/repos/org/repo/pulls" "W/\"abc123\"")
  ;; (add-etag-header {} "https://api.github.com/repos/org/repo/pulls")
  ;; => {"If-None-Match" "W/\"abc123\""}
  )
