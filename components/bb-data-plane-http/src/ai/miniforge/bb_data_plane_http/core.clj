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

(ns ai.miniforge.bb-data-plane-http.core
  "Implementation.

   Layer 0: pure config resolution + URL/cmd builders.
   Layer 1: lifecycle side effects (build, start, wait-ready, destroy).
   Layer 2: HTTP helpers that take an injectable `:http-fn`."
  (:require [ai.miniforge.bb-paths.interface :as paths]
            [ai.miniforge.bb-proc.interface :as proc]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]))

(def ^:const default-base-url     "http://127.0.0.1:8787")
(def ^:const default-base-url-env "THESIUM_DATA_PLANE_BASE_URL")

;------------------------------------------------------------------------------ Layer 0
;; Pure resolution.

(defn- under-root
  [root p]
  (cond
    (nil? p)                     nil
    (.startsWith ^String p "/")  p
    :else                        (str (or root (paths/repo-root)) "/" p)))

(defn resolve-base-url
  [{:keys [base-url base-url-env]
    :or   {base-url     default-base-url
           base-url-env default-base-url-env}}]
  (or (System/getenv base-url-env) base-url))

(defn binary-path
  [{:keys [root binary]}]
  (under-root root binary))

(defn manifest-path
  [{:keys [root manifest]}]
  (under-root root manifest))

(defn- build-cargo-cmd
  [manifest quiet?]
  (cond-> ["cargo" "build" "--release" "--manifest-path" manifest]
    quiet? (conj "--quiet")))

;------------------------------------------------------------------------------ Layer 1
;; Process lifecycle.

(defn build!
  [cfg {:keys [run-fn quiet?] :or {run-fn proc/run!}}]
  (apply run-fn (build-cargo-cmd (manifest-path cfg) quiet?))
  (binary-path cfg))

(defn start!
  [cfg {:keys [env state-dir state-dir-env log-path bg-fn]
        :or   {bg-fn proc/run-bg!}}]
  (let [env* (cond-> (or env {})
               (and state-dir state-dir-env) (assoc state-dir-env state-dir))
        opts (cond-> {}
               (seq env*) (assoc :extra-env env*)
               log-path   (assoc :out (java.io.File. ^String log-path)
                                 :err (java.io.File. ^String log-path)))]
    (bg-fn opts (binary-path cfg))))

(defn wait-ready!
  [health-url {:keys [max-attempts http-fn sleep-fn proc-handle]
               :or   {max-attempts 60
                      http-fn      #(http/get % {:throw false :timeout 1000})
                      sleep-fn     #(Thread/sleep 250)}}]
  (loop [attempt 1]
    (let [ready? (try
                   (let [resp (http-fn health-url)]
                     (and resp (< (:status resp) 500)))
                   (catch Exception _ false))
          dead?  (and proc-handle (not (p/alive? proc-handle)))]
      (cond
        ready?                    :ready
        dead?                     (throw (ex-info "Data plane exited during startup" {}))
        (>= attempt max-attempts) (throw (ex-info "Data plane did not become ready"
                                                  {:attempts max-attempts
                                                   :health-url health-url}))
        :else                     (do (sleep-fn) (recur (inc attempt)))))))

(defn destroy!
  [proc]
  (proc/destroy! proc))

;------------------------------------------------------------------------------ Layer 2
;; HTTP helpers.

(defn- default-http-get  [url]      (http/get  url {:throw false}))
(defn- default-http-post [url opts] (http/post url opts))

(defn http-get-body
  [url {:keys [http-fn] :or {http-fn default-http-get}}]
  (:body (http-fn url)))

(defn http-get-json
  [url {:keys [http-fn] :or {http-fn default-http-get}}]
  (let [resp (http-fn url)]
    (if (= 200 (:status resp))
      (json/parse-string (:body resp) true)
      (throw (ex-info (str "GET " url " failed")
                      {:status (:status resp) :body (:body resp)})))))

(defn http-post-json
  [url body-map {:keys [http-fn] :or {http-fn default-http-post}}]
  (let [resp (http-fn url
                      {:headers {"Content-Type" "application/json"}
                       :body    (json/generate-string body-map)
                       :throw   false})]
    (if (= 200 (:status resp))
      (json/parse-string (:body resp) true)
      (throw (ex-info (str "POST " url " failed")
                      {:status (:status resp) :body (:body resp)})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (resolve-base-url {})
  (build-cargo-cmd "/tmp/Cargo.toml" false)
  (under-root "/tmp/root" "bin/dp")

  :leave-this-here)
