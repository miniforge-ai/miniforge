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
(ns ai.miniforge.logging.http
  "JDK `java.net.http` request-builder helper shared by the fleet sinks.

   Lives in the `logging` brick because that's the most semantically
   aligned existing brick (the fleet sink IS a logging concern) and the
   `event-stream` brick already depends on `logging`. Promote to a
   dedicated `http-utils` brick if a third consumer outside the
   sink-layer arrives.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} invoke-static
  [class-name method-name param-types args]
  (.invoke (.getMethod (Class/forName class-name)
                       method-name
                       (into-array Class param-types))
           nil
           (object-array args)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-json-post
  "Build an HTTP POST request with a JSON string `body` and a Bearer
   `api-key` header. `timeout-ms` sets the request-level timeout via
   `HttpRequest.Builder.timeout`."
  [uri body api-key timeout-ms]
  (-> (invoke-static "java.net.http.HttpRequest" "newBuilder" [] [])
      (.uri (java.net.URI/create uri))
      (.timeout (java.time.Duration/ofMillis timeout-ms))
      (.header "Authorization" (str "Bearer " api-key))
      (.header "Content-Type" "application/json")
      (.POST (invoke-static "java.net.http.HttpRequest$BodyPublishers"
                            "ofString"
                            [String]
                            [body]))
      (.build)))
