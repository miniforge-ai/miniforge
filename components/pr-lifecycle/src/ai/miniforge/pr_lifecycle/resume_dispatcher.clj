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

(ns ai.miniforge.pr-lifecycle.resume-dispatcher
  "Resume Signal Dispatcher (N13 §2.7 §Dispatch).

   When a PR with registered listeners merges, build a structured
   resume primer per spec and deliver it to each `:active` listener
   via its declared channel, then transition the listener to
   `:dispatched`.

   v0 scope:
   - Polling-based trigger: walk active listeners, query GitHub for
     each PR's merged state, dispatch on transition. Webhook server
     is a v1 hardening.
   - `:webhook` channel: real HTTP POST with bounded retries.
   - `:pty` + `:miniforge-ipc` channels: typed `:not-yet-wired`
     errors. They require cooperating runtime infrastructure (Drive
     console PTY host for `:pty`; an event-stream subscriber for
     `:miniforge-ipc`) that v0 doesn't ship. Registered listeners
     using these channels surface as attention items per spec §6.

   Layer 0: config + primer schema
   Layer 1: pure helpers (build primer, classify channel)
   Layer 2: channel handlers (per-kind I/O)
   Layer 3: dispatcher entry points (orchestrate registry +
            channel + transition)"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.listener-registry :as registry]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration + primer schema

(def ^:private config
  "Component config loaded from
   `resources/config/resume-dispatcher/defaults.edn`. Tunables for
   webhook retry budget, request timeout, etc., live in EDN so
   operators don't recompile."
  (-> (io/resource "config/resume-dispatcher/defaults.edn")
      slurp
      edn/read-string))

(def webhook-max-attempts
  "Number of webhook POST attempts (initial + retries) before giving up."
  (:webhook/max-attempts config))

(def webhook-timeout-ms
  "Per-attempt timeout for the webhook POST."
  (:webhook/timeout-ms config))

(def webhook-backoff-base-ms
  "Initial backoff (doubled per retry)."
  (:webhook/backoff-base-ms config))

(def supported-channel-kinds
  "Channel kinds with a real handler in v0.
   `:pty` and `:miniforge-ipc` are recognized as valid registry
   values (per listener-registry config) but dispatch is not wired
   yet — they return `:resume-dispatcher/not-yet-wired`."
  #{:webhook})

(def ResumePrimer
  "Resume primer payload delivered to each listener (N13 §2.7)."
  [:map
   [:resume/pr-url       :string]
   [:resume/merge-sha    :string]
   [:resume/merged-at    inst?]
   [:resume/diff-summary {:optional true} [:maybe :string]]
   [:resume/listener     [:map
                          [:agent/id   :string]
                          [:session/id {:optional true} [:maybe :string]]]]])

;------------------------------------------------------------------------------ Layer 1
;; Pure helpers (primer construction + channel classification)

(defn build-primer
  "Pure: assemble the resume primer per N13 §2.7 from a merged-PR
   record + a single listener entry.

   `merged-pr` shape:
     {:pr/url       string
      :merge/sha    string
      :merged-at    inst
      :diff-summary string?  ; optional}"
  [merged-pr listener]
  {:resume/pr-url       (:pr/url merged-pr)
   :resume/merge-sha    (:merge/sha merged-pr)
   :resume/merged-at    (:merged-at merged-pr)
   :resume/diff-summary (:diff-summary merged-pr)
   :resume/listener     (cond-> {:agent/id (:agent/id listener)}
                          (:session/id listener) (assoc :session/id (:session/id listener)))})

(defn validate-primer!
  "Throw on invalid primer; return primer otherwise. Used by
   `dispatch-to-channel!` before any I/O."
  [primer]
  (when-not (m/validate ResumePrimer primer)
    (throw (ex-info "invalid resume primer"
                    {:errors  (m/explain ResumePrimer primer)
                     :anomaly :resume-dispatcher/invalid-primer
                     :primer  primer})))
  primer)

(defn channel-supported?
  "Pure predicate: true when the listener's channel kind has a real
   handler in v0."
  [listener]
  (contains? supported-channel-kinds
             (get-in listener [:resume-channel :channel/kind])))

(defn- not-yet-wired-error
  "Build the typed error returned for `:pty` / `:miniforge-ipc`
   listeners until the cooperating runtimes ship."
  [listener]
  (let [kind (get-in listener [:resume-channel :channel/kind])]
    (dag/err :resume-dispatcher/not-yet-wired
             (str "channel kind " kind " not yet wired in v0; "
                  "register listener with :webhook channel until "
                  "the cooperating runtime ships")
             {:channel/kind kind
              :listener/id  (:listener/id listener)})))

;------------------------------------------------------------------------------ Layer 2
;; Channel handlers (per-kind I/O)

(defn- backoff-ms
  "Pure: exponential backoff for webhook retries — base * 2^attempt."
  [attempt]
  (* webhook-backoff-base-ms (long (Math/pow 2 attempt))))

(defn- timeout-seconds
  "Convert `webhook-timeout-ms` to a curl `--max-time` string with
   millisecond precision. `curl --max-time` accepts decimals, so we
   format `<seconds>.<thousandths>` rather than `(quot ms 1000)` —
   the latter truncates to 0 if operators tune `:webhook/timeout-ms`
   below 1000ms, and `--max-time 0` disables the timeout entirely
   (would let the dispatcher hang on a slow/broken endpoint)."
  []
  (format "%.3f" (/ (double webhook-timeout-ms) 1000.0)))

(defn- post-webhook-once
  "Single HTTP POST attempt to the listener's `:channel/target` URL.
   Returns DAG result. Curl invoked via `babashka.process` to keep
   this brick BB-compatible without pulling extra HTTP deps."
  [target-url json-body]
  (try
    (let [r (process/shell {:in json-body
                            :out :string
                            :err :string
                            :continue true}
                           "curl" "--silent" "--show-error" "--fail"
                           "--max-time" (timeout-seconds)
                           "-X" "POST"
                           "-H" "Content-Type: application/json"
                           "--data-binary" "@-"
                           target-url)]
      (if (zero? (:exit r))
        (dag/ok {:body (str/trim (:out r ""))})
        (dag/err :resume-dispatcher/webhook-http-failed
                 (str "curl exit " (:exit r) ": " (str/trim (:err r "")))
                 {:exit-code (:exit r) :target target-url})))
    (catch Throwable e
      (dag/err :resume-dispatcher/webhook-exception
               (.getMessage e)
               {:target target-url}))))

(defn sleep!
  "Wrapper around `Thread/sleep`. Public (rather than `defn-`) so the
   retry loop's sleep can be `with-redefs`'d to a no-op in unit tests
   — `with-redefs` on a private var requires `#'ns/var` quoting which
   is verbose; promoting this to public is the cheaper test seam."
  [ms]
  (Thread/sleep (long ms)))

(defn- post-webhook-with-retry
  "Webhook POST with bounded retries + exponential backoff.

   Backoff schedule: a failed attempt at index `i` sleeps
   `(backoff-ms i)` before attempt `i+1`. The final attempt's
   failure does NOT sleep (no retry follows). Concretely with
   defaults (max-attempts=3, base=500ms):

     attempt 0 fails → sleep 500ms  → attempt 1
     attempt 1 fails → sleep 1000ms → attempt 2
     attempt 2 fails → return error (no sleep)

   Returns the first successful DAG result, or the last failure."
  [target-url json-body]
  (loop [attempt 0]
    (let [r (post-webhook-once target-url json-body)
          last-attempt? (>= (inc attempt) webhook-max-attempts)]
      (cond
        (dag/ok? r)
        r

        last-attempt?
        r

        :else
        (do
          (sleep! (backoff-ms attempt))
          (recur (inc attempt)))))))

(defn- dispatch-via-webhook!
  "Send the primer to the listener's webhook URL. Returns DAG result.
   Cheshire's default keyword serialization already preserves the
   `<ns>/<name>` form (e.g. `:resume/pr-url → \"resume/pr-url\"`),
   which is what the spec requires receivers to parse — no key-fn
   transformation needed."
  [primer listener]
  (let [target (get-in listener [:resume-channel :channel/target])
        body   (json/generate-string primer)]
    (cond
      (or (nil? target) (str/blank? target))
      (dag/err :resume-dispatcher/missing-target
               "listener :resume-channel/:channel/target is required for :webhook"
               {:listener/id (:listener/id listener)})

      :else
      (post-webhook-with-retry target body))))

(def ^:private channel-handlers
  "Channel-kind → handler. Closed v0 set; unsupported kinds route to
   `not-yet-wired-error`."
  {:webhook dispatch-via-webhook!})

;------------------------------------------------------------------------------ Layer 3
;; Dispatcher entry points (orchestrate registry + channel + transition)

(defn dispatch-to-channel!
  "Pure-ish wrapper: validate primer, look up channel handler,
   invoke. Returns DAG result. Does NOT mark the listener
   `:dispatched` — that's the caller's job after success."
  [primer listener]
  (try
    (validate-primer! primer)
    (let [kind (get-in listener [:resume-channel :channel/kind])
          handler (get channel-handlers kind)]
      (if handler
        (handler primer listener)
        (not-yet-wired-error listener)))
    (catch clojure.lang.ExceptionInfo e
      (dag/err (or (:anomaly (ex-data e)) :resume-dispatcher/invalid-primer)
               (.getMessage e)
               (ex-data e)))))

(defn dispatch-listener!
  "Build primer + dispatch + mark dispatched on success.
   Returns `(dag/ok {:listener/id ... :dispatch-id ... :outcome :delivered})`
   on success or the failing DAG result with `:listener/id` attached
   on `:data` for diagnostics."
  [worktree-path merged-pr listener]
  (let [primer (build-primer merged-pr listener)
        r      (dispatch-to-channel! primer listener)]
    (if-not (dag/ok? r)
      ;; Decorate with listener-id so an operator scanning the
      ;; failure stream can correlate without re-querying the registry.
      (update r :error (fn [err]
                         (-> err
                             (update :data (fnil assoc {})
                                     :listener/id (:listener/id listener)))))
      (let [dispatch-id (random-uuid)
            mark-r (registry/mark-dispatched!
                    worktree-path
                    (:pr/url merged-pr)
                    (:listener/id listener)
                    dispatch-id)]
        (if (dag/ok? mark-r)
          (dag/ok {:listener/id   (:listener/id listener)
                   :dispatch-id   dispatch-id
                   :outcome       :delivered
                   :dispatched-at (java.util.Date.)})
          ;; Delivery succeeded but marking failed — surface the
          ;; degraded state so the caller knows the registry is now
          ;; out of sync with the wire. The next pass will re-deliver.
          (dag/err :resume-dispatcher/marked-failed-after-delivery
                   (str "primer delivered but mark-dispatched! failed: "
                        (get-in mark-r [:error :message]))
                   {:listener/id (:listener/id listener)
                    :dispatch-id dispatch-id
                    :mark-error  (:error mark-r)}))))))

(defn dispatch-pr-merge!
  "Walk every `:active` listener for `pr-url` and dispatch each.
   Always returns a DAG result for shape consistency:

   - On registry-read failure: the upstream `(dag/err ...)` is bubbled
     unchanged so callers can branch on `(dag/ok? r)` rather than
     guarding for two return shapes.
   - On success (including zero active listeners): `(dag/ok summary)`
     where summary is `{:pr-url <s> :listener-count <int> :dispatched [...]
     :failed [...]}`.

   Per-listener failures don't abort the pass — they're collected
   into `:failed` and the overall call still returns `(dag/ok ...)`.

   `merged-pr` is the caller-provided merge record (see `build-primer`)."
  [worktree-path pr-url merged-pr]
  (let [r (registry/read-registry worktree-path)]
    (if-not (dag/ok? r)
      r
      (let [actives (registry/active-entries-for-pr (:data r) pr-url)
            summary (reduce
                     (fn [acc listener]
                       (let [d (dispatch-listener! worktree-path merged-pr listener)]
                         (if (dag/ok? d)
                           (update acc :dispatched conj (:data d))
                           (update acc :failed conj
                                   {:listener/id (:listener/id listener)
                                    :error       (:error d)}))))
                     {:pr-url pr-url
                      :listener-count (count actives)
                      :dispatched []
                      :failed     []}
                     actives)]
        (dag/ok summary)))))
