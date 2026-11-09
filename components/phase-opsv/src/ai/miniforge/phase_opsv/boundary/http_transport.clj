;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.boundary.http-transport
  "Raw HTTP acquisition keeps cancellation attached to the original JDK future."
  (:require [ai.miniforge.resource-cleanup.interface :as cleanup]
            [babashka.http-client :as http]
            [clojure.string :as str])
  (:import [java.net HttpURLConnection]
           [java.net.http HttpResponse]
           [java.util Locale]
           [java.util.concurrent CompletableFuture]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} success-status
  "HTTP OK supplies the complete representation required by observation admission."
  HttpURLConnection/HTTP_OK)

(def ^{:stratum 0} created-status
  "Only HTTP Created acknowledges creation of a previously absent native resource."
  HttpURLConnection/HTTP_CREATED)

(def ^{:stratum 0} not-found-status
  "HTTP absence is distinct from JSON null; only declared optional resources may be absent."
  HttpURLConnection/HTTP_NOT_FOUND)

(def ^{:stratum 0} ^:private timeout-classes
  #{"java.net.http.HttpTimeoutException" "java.util.concurrent.TimeoutException"})

(def ^{:stratum 0} identity-coding
  "No-transform coding only; bounded response reads never decompress content."
  "identity")

(defn- ^{:stratum 0} header-codings [header] (str/split header #"," -1))

(def ^{:stratum 0} ^:private client
  (delay (http/client {:follow-redirects :never})))

(defn ^{:stratum 0} response-fields [^HttpResponse response]
  (let [encodings (vec (.allValues (.headers response) "content-encoding"))]
    {:status (.statusCode response)
     :body (.body response)
     :encodings encodings}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} json-get-options [uri token timeout-ms params]
  {:uri uri
   :method :get
   :headers {"Accept" "application/json"
             "Accept-Encoding" identity-coding}
   :oauth-token token
   :query-params params
   :timeout timeout-ms
   :async true
   :raw true})

(defn- ^{:stratum 1} identity-coding? [coding]
  (= identity-coding (.toLowerCase ^String (str/trim coding) Locale/ROOT)))

(defn- ^{:stratum 1} timeout? [failure]
  ;; Some JDK timeout classes are not exposed by Babashka's class resolver.
  (->> (class failure)
       (iterate #(.getSuperclass ^Class %))
       (take-while some?)
       (map #(.getName ^Class %))
       (some timeout-classes)))

(defn ^{:stratum 1} release! [^CompletableFuture pending]
  (.cancel pending true)
  ;; A deadline can race a completed response. Close it even when await lost
  ;; that race; successful cancellation leaves disposal to the HTTP client.
  (when-not (.isCompletedExceptionally pending)
    (some-> (.getNow pending nil) response-fields :body .close)))

(defn ^{:stratum 1} start! [options]
  (http/request (assoc options :client @client)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unencoded? [encodings]
  (every? identity-coding? (mapcat header-codings encodings)))

(defn ^{:stratum 2} timeout-cause [failure] (cleanup/find-cause timeout? failure))

(comment ::owned-http-response-transport)
