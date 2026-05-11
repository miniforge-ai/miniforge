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

(ns ai.miniforge.pr-lifecycle.resume-dispatcher-test
  "Tests for the N13 §2.7 Resume Signal Dispatcher."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.listener-registry :as registry]
            [ai.miniforge.pr-lifecycle.resume-dispatcher :as sut]))

;; ── fixture: ephemeral worktree ──────────────────────────────────────

(def ^:dynamic *worktree* nil)

(defn worktree-fixture [f]
  (let [w (str (fs/create-temp-dir {:prefix "resume-dispatcher-test-"}))]
    (try
      (binding [*worktree* w] (f))
      (finally (try (fs/delete-tree w) (catch Throwable _ nil))))))

(use-fixtures :each worktree-fixture)

;; ── fixtures ─────────────────────────────────────────────────────────

(def ^:private pr-url "https://github.com/o/r/pull/42")

(def ^:private base-listener-params
  {:pr/url         pr-url
   :pr/repo-id     "o/r"
   :pr/number      42
   :agent/id       "agent-A"
   :runtime        :claude-cli
   :resume-channel {:channel/kind   :webhook
                    :channel/target "https://hooks.example.com/agent-A"}
   :registered-by  :authoring-agent})

(def ^:private merged-pr
  {:pr/url       pr-url
   :merge/sha    "abc1234deadbeef"
   :merged-at    #inst "2026-05-10T20:54:45.000-00:00"
   :diff-summary "+50 / -12 across 3 files"})

(defn- register-listener!
  [overrides]
  (let [r (registry/register! *worktree* (merge base-listener-params overrides))]
    (assert (dag/ok? r))
    (-> r :data :listener-id)))

(defn- capture-shell
  [calls-atom result]
  (fn [opts & args]
    (swap! calls-atom conj {:opts opts :args (vec args)})
    result))

(defn- entry-by-id
  "Read registry from disk, find entry by listener-id."
  [listener-id]
  (let [reg (-> (registry/read-registry *worktree*) :data)
        bucket (registry/entries-for-pr reg pr-url)]
    (first (filter #(= listener-id (:listener/id %)) bucket))))

;; ── build-primer (pure) ──────────────────────────────────────────────

(deftest build-primer-shape
  (testing "primer has all spec-required keys + omits :session/id when absent"
    (let [listener (assoc base-listener-params :session/id nil :listener/id (random-uuid))
          primer (sut/build-primer merged-pr listener)]
      (is (= pr-url (:resume/pr-url primer)))
      (is (= "abc1234deadbeef" (:resume/merge-sha primer)))
      (is (= #inst "2026-05-10T20:54:45.000-00:00" (:resume/merged-at primer)))
      (is (= "+50 / -12 across 3 files" (:resume/diff-summary primer)))
      (is (= "agent-A" (get-in primer [:resume/listener :agent/id])))
      (is (not (contains? (:resume/listener primer) :session/id))
          "session/id is dropped when nil — spec marks it optional"))))

(deftest build-primer-includes-session-when-present
  (let [listener (assoc base-listener-params :session/id "sess-001" :listener/id (random-uuid))
        primer (sut/build-primer merged-pr listener)]
    (is (= "sess-001" (get-in primer [:resume/listener :session/id])))))

(deftest validate-primer-throws-on-bad-shape
  (testing "validate-primer! throws ex-info with anomaly tag on missing required keys"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/validate-primer! {:resume/pr-url "x"})))))

;; ── channel-supported? + not-yet-wired ───────────────────────────────

(deftest channel-supported-only-webhook-in-v0
  (is (true?  (sut/channel-supported?
               (assoc base-listener-params
                      :resume-channel {:channel/kind :webhook
                                       :channel/target "x"}))))
  (is (false? (sut/channel-supported?
               (assoc base-listener-params
                      :resume-channel {:channel/kind :pty
                                       :channel/target "pty-7"}))))
  (is (false? (sut/channel-supported?
               (assoc base-listener-params
                      :resume-channel {:channel/kind :miniforge-ipc
                                       :channel/target "topic-x"})))))

(deftest dispatch-to-channel-pty-returns-not-yet-wired
  (testing ":pty listener gets :resume-dispatcher/not-yet-wired with kind in :data"
    (let [listener (assoc base-listener-params
                          :listener/id (random-uuid)
                          :resume-channel {:channel/kind :pty
                                           :channel/target "pty-7"})
          primer (sut/build-primer merged-pr listener)
          r (sut/dispatch-to-channel! primer listener)]
      (is (not (dag/ok? r)))
      (is (= :resume-dispatcher/not-yet-wired (get-in r [:error :code])))
      (is (= :pty (get-in r [:error :data :channel/kind]))))))

(deftest dispatch-to-channel-miniforge-ipc-returns-not-yet-wired
  (let [listener (assoc base-listener-params
                        :listener/id (random-uuid)
                        :resume-channel {:channel/kind :miniforge-ipc
                                         :channel/target "topic-x"})
        primer (sut/build-primer merged-pr listener)
        r (sut/dispatch-to-channel! primer listener)]
    (is (= :miniforge-ipc (get-in r [:error :data :channel/kind])))))

;; ── webhook handler ──────────────────────────────────────────────────

(deftest dispatch-webhook-happy-path
  (testing "successful webhook POST shells out to curl with the right args + JSON body"
    (let [calls (atom [])
          stub  (capture-shell calls {:exit 0 :out "" :err ""})]
      (with-redefs [process/shell stub]
        (let [listener (assoc base-listener-params :listener/id (random-uuid))
              primer (sut/build-primer merged-pr listener)
              r (sut/dispatch-to-channel! primer listener)]
          (is (dag/ok? r))
          (is (= 1 (count @calls)))
          (let [args (:args (first @calls))]
            (is (some #{"curl"} args))
            (is (some #{"-X" "POST"} args))
            (is (some #{"https://hooks.example.com/agent-A"} args))
            (is (some #{"Content-Type: application/json"} args)))
          (let [stdin (get-in (first @calls) [:opts :in])
                payload (json/parse-string stdin true)]
            (is (= pr-url (:resume/pr-url payload)))
            (is (= "abc1234deadbeef" (:resume/merge-sha payload)))))))))

(deftest dispatch-webhook-passes-fractional-seconds-to-curl
  (testing "curl --max-time receives fractional seconds (so a sub-1000ms timeout doesn't truncate to 0)"
    (let [calls (atom [])
          stub  (capture-shell calls {:exit 0 :out "" :err ""})]
      (with-redefs [process/shell stub
                    ;; Simulate operator tuning timeout below 1000ms.
                    sut/webhook-timeout-ms 250]
        (let [listener (assoc base-listener-params :listener/id (random-uuid))
              primer (sut/build-primer merged-pr listener)]
          (sut/dispatch-to-channel! primer listener)
          (let [args (:args (first @calls))
                idx  (.indexOf ^java.util.List args "--max-time")
                seconds-str (when (>= idx 0) (nth args (inc idx)))]
            (is (= "0.250" seconds-str)
                "fractional precision preserved; --max-time would truncate to 0 if we used (quot ms 1000)")
            (is (not= "0" seconds-str)
                "--max-time 0 would disable the timeout entirely (would let dispatcher hang)")))))))

(deftest dispatch-webhook-rejects-blank-target
  (let [listener (assoc base-listener-params
                        :listener/id (random-uuid)
                        :resume-channel {:channel/kind :webhook :channel/target ""})
        primer (sut/build-primer merged-pr listener)
        r (sut/dispatch-to-channel! primer listener)]
    (is (not (dag/ok? r)))
    (is (= :resume-dispatcher/missing-target (get-in r [:error :code])))))

(deftest dispatch-webhook-retries-on-failure
  (testing "transient failure is retried up to webhook-max-attempts; success on retry → ok"
    (let [calls (atom 0)
          ;; Fail twice, succeed on third.
          stub (fn [_opts & _args]
                 (swap! calls inc)
                 (if (< @calls 3)
                   {:exit 22 :out "" :err "HTTP/1.1 503 Service Unavailable"}
                   {:exit 0  :out "" :err ""}))]
      (with-redefs [process/shell stub
                    ;; Skip the real Thread/sleep so the suite stays fast.
                    sut/sleep! (fn [_] nil)]
        (let [listener (assoc base-listener-params :listener/id (random-uuid))
              primer (sut/build-primer merged-pr listener)
              r (sut/dispatch-to-channel! primer listener)]
          (is (dag/ok? r))
          (is (= 3 @calls) "retried twice before success"))))))

(deftest dispatch-webhook-backoff-schedule
  (testing "backoff sleeps after attempts 0 + 1 (with backoff-ms 0 + 1) but NOT after attempt 2 (last)"
    (let [sleeps (atom [])
          ;; Always fail so we exhaust all attempts.
          stub   (fn [_opts & _args]
                   {:exit 22 :out "" :err "HTTP/1.1 500 Internal Server Error"})]
      (with-redefs [process/shell stub
                    sut/sleep! (fn [ms] (swap! sleeps conj ms))]
        (let [listener (assoc base-listener-params :listener/id (random-uuid))
              primer (sut/build-primer merged-pr listener)
              _ (sut/dispatch-to-channel! primer listener)]
          (is (= [(* sut/webhook-backoff-base-ms 1)   ; sleep before retry to attempt 1
                  (* sut/webhook-backoff-base-ms 2)]  ; sleep before retry to attempt 2
                 @sleeps)
              "backoff schedule: base*1 then base*2; no sleep after the final attempt"))))))

(deftest dispatch-webhook-exhausts-attempts
  (testing "persistent failure exhausts webhook-max-attempts and returns the last error"
    (let [calls (atom 0)
          stub (fn [_opts & _args]
                 (swap! calls inc)
                 {:exit 22 :out "" :err "HTTP/1.1 500 Internal Server Error"})]
      (with-redefs [process/shell stub
                    sut/sleep! (fn [_] nil)]
        (let [listener (assoc base-listener-params :listener/id (random-uuid))
              primer (sut/build-primer merged-pr listener)
              r (sut/dispatch-to-channel! primer listener)]
          (is (not (dag/ok? r)))
          (is (= sut/webhook-max-attempts @calls)
              "called exactly webhook-max-attempts times")
          (is (= :resume-dispatcher/webhook-http-failed (get-in r [:error :code]))))))))

;; ── dispatch-listener! (delivery + mark) ─────────────────────────────

(deftest dispatch-listener-marks-on-success
  (testing "successful delivery transitions listener :active → :dispatched and stamps dispatch-id"
    (let [stub (capture-shell (atom []) {:exit 0 :out "" :err ""})]
      (with-redefs [process/shell stub]
        (let [lid (register-listener! {})
              listener (entry-by-id lid)
              r (sut/dispatch-listener! *worktree* merged-pr listener)]
          (is (dag/ok? r))
          (is (= :delivered (-> r :data :outcome)))
          (is (uuid? (-> r :data :dispatch-id)))
          (let [post (entry-by-id lid)]
            (is (= :dispatched (:status post)))
            (is (= (-> r :data :dispatch-id) (:resume/dispatch-id post)))
            (is (inst? (:resume/dispatched-at post)))))))))

(deftest dispatch-listener-decorates-failure-with-listener-id
  (testing "delivery failure surfaces :listener/id on :error :data for diagnostics"
    (let [stub (capture-shell (atom []) {:exit 22 :out "" :err "boom"})]
      (with-redefs [process/shell stub]
        (let [lid (register-listener! {})
              listener (entry-by-id lid)
              r (sut/dispatch-listener! *worktree* merged-pr listener)]
          (is (not (dag/ok? r)))
          (is (= lid (get-in r [:error :data :listener/id])))
          (let [post (entry-by-id lid)]
            (is (= :active (:status post))
                "listener stays :active when delivery fails — next pass retries")))))))

;; ── dispatch-pr-merge! (whole-PR pass) ───────────────────────────────

(deftest dispatch-pr-merge-walks-only-active-listeners
  (testing "dispatch-pr-merge! processes :active listeners; skips already-dispatched/cancelled"
    (let [stub (capture-shell (atom []) {:exit 0 :out "" :err ""})]
      (with-redefs [process/shell stub]
        (let [a (register-listener! {:agent/id "agent-A"})
              b (register-listener! {:agent/id "agent-B"})
              c (register-listener! {:agent/id "agent-C"})
              ;; Cancel b; pre-dispatch c via the registry directly to
              ;; simulate a previous run.
              _ (registry/unregister! *worktree* pr-url b)
              _ (registry/mark-dispatched! *worktree* pr-url c (random-uuid))
              r (sut/dispatch-pr-merge! *worktree* pr-url merged-pr)
              summary (:data r)]
          (is (dag/ok? r) "dispatch-pr-merge! always returns a DAG result")
          (is (= pr-url (:pr-url summary)))
          (is (= 1 (:listener-count summary))
              "only the still-active listener (a) is in scope")
          (is (= 1 (count (:dispatched summary))))
          (is (= 0 (count (:failed summary))))
          (is (= a (-> summary :dispatched first :listener/id))))))))

(deftest dispatch-pr-merge-collects-failures-without-aborting
  (testing "per-listener failures don't abort the pass — they're collected"
    ;; Stub by URL match so per-listener outcomes are deterministic.
    ;; agent-A targets the failing endpoint; agent-B targets the OK one.
    (let [stub (fn [_opts & args]
                 (cond
                   (some #{"https://hooks.example.com/fail"} args)
                   {:exit 22 :out "" :err "boom"}

                   (some #{"https://hooks.example.com/ok"} args)
                   {:exit 0 :out "" :err ""}

                   :else
                   {:exit 1 :out "" :err "no route"}))]
      (with-redefs [process/shell stub
                    sut/sleep! (fn [_] nil)]
        (let [_a (register-listener!
                  {:agent/id "agent-A"
                   :resume-channel {:channel/kind   :webhook
                                    :channel/target "https://hooks.example.com/fail"}})
              _b (register-listener!
                  {:agent/id "agent-B"
                   :resume-channel {:channel/kind   :webhook
                                    :channel/target "https://hooks.example.com/ok"}})
              r (sut/dispatch-pr-merge! *worktree* pr-url merged-pr)
              summary (:data r)]
          (is (dag/ok? r))
          (is (= 2 (:listener-count summary)))
          (is (= 1 (count (:failed summary)))
              "agent-A failure collected; agent-B unaffected")
          (is (= 1 (count (:dispatched summary)))
              "agent-B success despite agent-A failure"))))))

(deftest dispatch-pr-merge-pty-listener-not-yet-wired
  (testing ":pty listener flows through as a :failed entry with not-yet-wired"
    (let [pty-listener-id (register-listener!
                           {:agent/id "pty-agent"
                            :resume-channel {:channel/kind :pty
                                             :channel/target "pty-1"}})
          r (sut/dispatch-pr-merge! *worktree* pr-url merged-pr)
          summary (:data r)]
      (is (dag/ok? r))
      (is (= 1 (count (:failed summary))))
      (is (= :resume-dispatcher/not-yet-wired
             (get-in (first (:failed summary)) [:error :code])))
      (is (= pty-listener-id (get-in (first (:failed summary)) [:error :data :listener/id]))))))

(deftest dispatch-pr-merge-bubbles-registry-read-failure-as-dag-err
  (testing "registry read failure bubbles up as the same DAG error shape (not a partial summary)"
    (with-redefs [registry/read-registry
                  (fn [_]
                    {:ok? false
                     :error {:code :listener-registry/read-failed
                             :message "synthetic disk failure"
                             :data {:path "/dev/null"}}})]
      (let [r (sut/dispatch-pr-merge! *worktree* pr-url merged-pr)]
        (is (not (dag/ok? r))
            "registry-read failure bubbles as DAG err, not a half-built summary")
        (is (= :listener-registry/read-failed (get-in r [:error :code])))
        (is (nil? (:dispatched r))
            "no summary keys present on the err return — caller branches on dag/ok?")))))
