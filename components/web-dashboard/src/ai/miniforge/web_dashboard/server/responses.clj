;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.server.responses
  "HTTP response helpers and static file serving."
  (:require
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [hiccup2.core :refer [html]]))

;------------------------------------------------------------------------------ Layer 0
;; Content types

(def content-types
  "MIME types for static files."
  {".html" "text/html"
   ".css"  "text/css"
   ".js"   "application/javascript"
   ".json" "application/json"
   ".png"  "image/png"
   ".jpg"  "image/jpeg"
   ".jpeg" "image/jpeg"
   ".svg"  "image/svg+xml"
   ".gif"  "image/gif"
   ".ico"  "image/x-icon"})

(defn get-content-type
  "Get content type from file extension."
  [path]
  (or (some (fn [[ext type]]
              (when (.endsWith path ext) type))
            content-types)
      "application/octet-stream"))

;------------------------------------------------------------------------------ Layer 1
;; Response constructors

(defn serve-static-file
  "Serve a static file from resources/public."
  [path]
  (if-let [resource (io/resource (str "public" path))]
    {:status 200
     :headers {"Content-Type" (get-content-type path)}
     :body (io/input-stream resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found"}))

(defn html-response
  "Render hiccup to HTML and return as HTTP response."
  [hiccup-data]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (if (string? hiccup-data)
           hiccup-data
           (str (html hiccup-data)))})

(defn json-response
  "Return JSON response."
  [data]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string data)})

(defn not-found-response
  "Return 404 response."
  []
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not Found"})
