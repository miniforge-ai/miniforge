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
(ns ai.miniforge.webhook-intake.handler
  "Ring handler for GitHub webhooks. POST /webhooks/github verifies the
   delivery's X-Hub-Signature-256 against the webhook secret, maps it with
   `github/delivery->events`, and publishes each event to an event stream,
   where workflow triggers pick it up."
  (:require
   [ai.miniforge.webhook-intake.publish :as publish]
   [ai.miniforge.webhook-intake.signature :as signature]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} github-path
  "Path GitHub is configured to deliver to."
  "/webhooks/github")

(defn ^{:stratum 0} json-response
  "A JSON response with `status` and `body`."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn ^{:stratum 0} body-bytes
  "The request body as bytes; empty when there is none."
  ^bytes [body]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (when body (io/copy body out))
    (.toByteArray out)))

(defn ^{:stratum 0} parse-payload
  "The JSON body with keyword keys, or nil when it is not JSON."
  [^bytes body]
  (try+
    (json/parse-string (String. body "UTF-8") true)
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} handle-github
  "Handle one request to `github-path`. `intake` holds :secret, :stream
   and :seen; see `publish/delivery-response` for redeliveries."
  [{:keys [secret stream seen]} req]
  (let [body (body-bytes (:body req))
        {:strs [x-hub-signature-256 x-github-event x-github-delivery]} (:headers req)]
    (cond
      (not= :post (:request-method req))
      (json-response 405 {:error "method-not-allowed"})

      (not (signature/valid-signature? secret body x-hub-signature-256))
      (json-response 401 {:error "signature-invalid"})

      (str/blank? x-github-delivery)
      (json-response 400 {:error "delivery-id-missing"})

      :else
      (if-let [payload (parse-payload body)]
        (apply json-response (publish/delivery-response {:stream stream :seen seen}
                                                        {:event-name x-github-event
                                                         :delivery-id x-github-delivery
                                                         :payload payload}
                                                        x-hub-signature-256))
        (json-response 400 {:error "payload-invalid"})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-handler
  "Ring handler for the intake: `github-path`, else 404."
  [intake]
  (fn [req]
    (if (= github-path (:uri req))
      (handle-github intake req)
      (json-response 404 {:error "not-found"}))))
