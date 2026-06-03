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

(ns ai.miniforge.event-stream.test-helpers.http-mock
  "Shared `java.net.http.HttpClient` stub for sink tests.

   Extracted from sinks_test.clj so the test file stays focused on
   assertions, and so other sink tests (e.g. a future
   logging/fleet-sink_test) can reuse the same stub shape.")

(defn mock-http-client
  "Construct a minimal `HttpClient` stub for tests.
   `send-fn` is called as `(send-fn req handler)` and must return an
   `HttpResponse`; defaults to a 200 no-op.  All other abstract methods
   return safe defaults so accidental calls don't throw
   `AbstractMethodError` (java.net.http.HttpClient has ~10 abstract
   methods beyond `send`)."
  [& [send-fn]]
  (let [default-resp (reify java.net.http.HttpResponse
                       (statusCode [_] 200))
        f            (or send-fn (fn [_req _handler] default-resp))]
    (proxy [java.net.http.HttpClient] []
      (send [req handler] (f req handler))
      (sendAsync
        ([req handler]
         (java.util.concurrent.CompletableFuture/completedFuture (f req handler)))
        ([req handler _push]
         (java.util.concurrent.CompletableFuture/completedFuture (f req handler))))
      (cookieHandler   [] (java.util.Optional/empty))
      (connectTimeout  [] (java.util.Optional/empty))
      (followRedirects [] java.net.http.HttpClient$Redirect/NORMAL)
      (sslContext      [] (javax.net.ssl.SSLContext/getDefault))
      (sslParameters   [] (javax.net.ssl.SSLParameters.))
      (authenticator   [] (java.util.Optional/empty))
      (version         [] java.net.http.HttpClient$Version/HTTP_2)
      (executor        [] (java.util.Optional/empty)))))
