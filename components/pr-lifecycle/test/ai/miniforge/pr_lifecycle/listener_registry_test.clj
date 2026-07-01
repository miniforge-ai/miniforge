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

(ns ai.miniforge.pr-lifecycle.listener-registry-test
  "Tests for the N13 §2.7 listener registry persistence primitive."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.listener-registry :as reg]))

;; ── fixture: ephemeral worktree ──────────────────────────────────────

(def ^:dynamic *worktree* nil)

(defn worktree-fixture [f]
  (let [w (str (fs/create-temp-dir {:prefix "listener-registry-test-"}))]
    (try
      (binding [*worktree* w] (f))
      (finally (try (fs/delete-tree w) (catch Throwable _ nil))))))

(use-fixtures :each worktree-fixture)

;; ── helpers ──────────────────────────────────────────────────────────

(def ^:private base-params
  {:pr/url         "https://github.com/o/r/pull/42"
   :pr/repo-id     "o/r"
   :pr/number      42
   :agent/id       "agent-A"
   :runtime        :claude-cli
   :resume-channel {:channel/kind   :pty
                    :channel/target "pty-7"}
   :registered-by  :authoring-agent})

(defn- count-listeners-on-disk
  "Read the file directly and tally entries (cross-checks our pure ops)."
  []
  (let [path (str (fs/path *worktree* reg/default-storage-path))]
    (if (fs/exists? path)
      (let [reg (edn/read-string (slurp path))]
        (reduce + 0 (map count (vals (:registry/listeners reg)))))
      0)))

;; ── read-registry ────────────────────────────────────────────────────

(deftest read-on-missing-file-yields-empty-registry
  (let [r (reg/read-registry *worktree*)]
    (is (dag/ok? r))
    (is (= reg/registry-version (-> r :data :registry/version)))
    (is (= {} (-> r :data :registry/listeners)))))

(deftest read-on-blank-file-yields-empty-registry
  (let [path (str (fs/path *worktree* reg/default-storage-path))]
    (fs/create-dirs (fs/parent path))
    (spit path "")
    (let [r (reg/read-registry *worktree*)]
      (is (dag/ok? r))
      (is (= reg/registry-version (-> r :data :registry/version))))))

(deftest read-on-corrupt-edn-returns-typed-error
  (let [path (str (fs/path *worktree* reg/default-storage-path))]
    (fs/create-dirs (fs/parent path))
    (spit path "this is { not edn")
    (let [r (reg/read-registry *worktree*)]
      (is (not (dag/ok? r)))
      (is (= :listener-registry/read-failed (get-in r [:error :code]))))))

;; ── register! ────────────────────────────────────────────────────────

(deftest register-happy-path
  (testing "register! persists an entry and returns its id"
    (let [r (reg/register! *worktree* base-params)]
      (is (dag/ok? r))
      (is (uuid? (-> r :data :listener-id)))
      (is (= 1 (count-listeners-on-disk))))))

(deftest register-rejects-bad-registered-by
  (testing "registration outside the three canonical moments is rejected (spec §Registration moments)"
    (doseq [bad [:rogue :unknown nil "authoring-agent"]]
      (let [r (reg/register! *worktree* (assoc base-params :registered-by bad))]
        (is (not (dag/ok? r))
            (str "input " (pr-str bad)))
        (is (= :listener-registry/invalid-registered-by (get-in r [:error :code])))))
    (is (zero? (count-listeners-on-disk))
        "no entry written when registration is rejected")))

(deftest register-rejects-malformed-entry
  (testing "missing required fields fail malli validation, surface typed error"
    (let [r (reg/register! *worktree* (dissoc base-params :pr/number))]
      (is (not (dag/ok? r)))
      (is (= :listener-registry/invalid-entry (get-in r [:error :code]))))
    (is (zero? (count-listeners-on-disk)))))

(deftest register-multiple-entries-on-same-pr
  (testing "multiple agents may bind to the same PR"
    (let [r1 (reg/register! *worktree* base-params)
          r2 (reg/register! *worktree* (assoc base-params :agent/id "agent-B"))]
      (is (dag/ok? r1))
      (is (dag/ok? r2))
      (is (not= (-> r1 :data :listener-id) (-> r2 :data :listener-id))
          "each registration gets a fresh listener-id")
      (is (= 2 (count-listeners-on-disk))))))

(deftest register-defaults-ttl
  (let [r (reg/register! *worktree* (dissoc base-params :ttl-seconds))]
    (is (dag/ok? r))
    (let [reg-state (:data (reg/read-registry *worktree*))
          entry (first (reg/active-entries-for-pr reg-state (:pr/url base-params)))]
      (is (= reg/default-ttl-seconds (:ttl-seconds entry))))))

;; ── transitions ──────────────────────────────────────────────────────

(deftest unregister-marks-cancelled
  (let [r1 (reg/register! *worktree* base-params)
        lid (-> r1 :data :listener-id)
        r2 (reg/unregister! *worktree* (:pr/url base-params) lid)]
    (is (dag/ok? r2))
    (let [entries (reg/entries-for-pr (:data (reg/read-registry *worktree*))
                                      (:pr/url base-params))]
      (is (= 1 (count entries)))
      (is (= :cancelled (:status (first entries)))))))

(deftest unregister-missing-listener-returns-typed-error
  (let [r (reg/unregister! *worktree* (:pr/url base-params) (random-uuid))]
    (is (not (dag/ok? r)))
    (is (= :listener-registry/listener-not-found (get-in r [:error :code])))))

(deftest mark-dispatched-records-dispatch-id-and-timestamp
  (let [r1 (reg/register! *worktree* base-params)
        lid (-> r1 :data :listener-id)
        did (random-uuid)
        r2 (reg/mark-dispatched! *worktree* (:pr/url base-params) lid did)]
    (is (dag/ok? r2))
    (let [entries (reg/entries-for-pr (:data (reg/read-registry *worktree*))
                                      (:pr/url base-params))
          entry (first entries)]
      (is (= :dispatched (:status entry)))
      (is (= did (:resume/dispatch-id entry)))
      (is (inst? (:resume/dispatched-at entry))))))

(deftest cancel-on-pr-close-transitions-only-active-entries
  (testing "actives become :cancelled, already-dispatched/cancelled stay put"
    (let [_  (reg/register! *worktree* base-params)
          r2 (reg/register! *worktree* (assoc base-params :agent/id "agent-B"))
          _  (reg/mark-dispatched! *worktree* (:pr/url base-params)
                                   (-> r2 :data :listener-id)
                                   (random-uuid))
          r3 (reg/mark-cancelled-on-pr-close! *worktree* (:pr/url base-params))]
      (is (dag/ok? r3))
      (is (= 1 (-> r3 :data :cancelled-count))
          "only the still-active entry transitions; the :dispatched one is left alone")
      (let [statuses (->> (reg/entries-for-pr
                           (:data (reg/read-registry *worktree*))
                           (:pr/url base-params))
                          (map :status)
                          set)]
        (is (= #{:cancelled :dispatched} statuses))))))

;; ── auto-expiry ──────────────────────────────────────────────────────

(deftest auto-expirable-predicate
  (let [now    (java.util.Date.)
        old    (java.util.Date. (- (.getTime now)
                                   (* 1000 (+ reg/default-ttl-seconds 60))))
        recent (java.util.Date. (- (.getTime now) 1000))
        active-old   {:status :active :registered-at old   :ttl-seconds reg/default-ttl-seconds}
        active-fresh {:status :active :registered-at recent :ttl-seconds reg/default-ttl-seconds}
        cancelled-old {:status :cancelled :registered-at old :ttl-seconds reg/default-ttl-seconds}]
    (is (true?  (reg/auto-expirable? active-old now)))
    (is (false? (reg/auto-expirable? active-fresh now)))
    (is (false? (reg/auto-expirable? cancelled-old now))
        "non-active entries don't auto-expire")))

;; Deterministic registry construction: build the snapshot directly
;; via pure helpers so we can dial `:registered-at` into the past
;; without touching wall-clock time. Avoids the Thread/sleep
;; flakiness that bites under CI load.

(defn- write-state!
  "Build a registry from `entries`, write it through `write-registry!`,
   and return the entries actually persisted. `entries` are caller-
   supplied entry maps (must include all required fields)."
  [entries]
  (let [reg (reduce reg/add-entry reg/empty-registry entries)
        r   (reg/write-registry! *worktree* reg)]
    (assert (dag/ok? r) "test fixture failed to write registry")
    entries))

(defn- entry
  "Build a fully-shaped ListenerEntry for tests with the supplied
   overrides. Required keys default from base-params; `:registered-at`
   defaults to now."
  [overrides]
  (merge {:listener/id     (random-uuid)
          :pr/url          (:pr/url base-params)
          :pr/repo-id      (:pr/repo-id base-params)
          :pr/number       (:pr/number base-params)
          :agent/id        (:agent/id base-params)
          :session/id      nil
          :runtime         (:runtime base-params)
          :resume-channel  (:resume-channel base-params)
          :registered-at   (java.util.Date.)
          :registered-by   (:registered-by base-params)
          :ttl-seconds     reg/default-ttl-seconds
          :status          :active
          :notes           nil}
         overrides))

(defn- past-inst
  "Return an inst `seconds-ago` before now."
  [seconds-ago]
  (java.util.Date. (- (.getTime (java.util.Date.)) (* 1000 seconds-ago))))

(deftest sweep-expired-transitions-stale-actives
  (testing "sweep-expired! flips :active entries past TTL to :expired (deterministic)"
    (let [stale (entry {:agent/id "agent-A"
                        :ttl-seconds 60
                        :registered-at (past-inst (* 60 60))}) ; 1 hour ago, ttl 60s
          fresh (entry {:agent/id "agent-B"
                        :ttl-seconds reg/default-ttl-seconds
                        :registered-at (java.util.Date.)})]
      (write-state! [stale fresh])
      (let [r (reg/sweep-expired! *worktree*)]
        (is (dag/ok? r))
        (is (= 1 (-> r :data :expired-count))
            "only the stale entry expires; the fresh one survives")
        (let [statuses (->> (reg/entries-for-pr
                             (:data (reg/read-registry *worktree*))
                             (:pr/url base-params))
                            (map :status)
                            set)]
          (is (= #{:expired :active} statuses)))))))

(deftest sweep-expired-is-idempotent
  (testing "running sweep twice yields 0 the second time (deterministic)"
    (write-state! [(entry {:agent/id "agent-A"
                           :ttl-seconds 60
                           :registered-at (past-inst (* 60 60))})])
    (let [r1 (reg/sweep-expired! *worktree*)
          r2 (reg/sweep-expired! *worktree*)]
      (is (= 1 (-> r1 :data :expired-count)))
      (is (= 0 (-> r2 :data :expired-count))))))

;; ── lookup ───────────────────────────────────────────────────────────

(deftest lookup-by-pr-and-by-agent
  (let [_ (reg/register! *worktree* base-params)
        _ (reg/register! *worktree* (assoc base-params :agent/id "agent-B"))
        _ (reg/register! *worktree* (assoc base-params
                                           :pr/url "https://github.com/o/r/pull/99"
                                           :pr/number 99))
        registry (:data (reg/read-registry *worktree*))]
    (is (= 2 (count (reg/entries-for-pr registry (:pr/url base-params)))))
    (is (= 1 (count (reg/entries-for-pr registry "https://github.com/o/r/pull/99"))))
    (is (= 2 (count (reg/entries-for-agent registry "agent-A")))
        "agent-A is bound to PR 42 and PR 99")
    (is (= 1 (count (reg/entries-for-agent registry "agent-B"))))))

;; ── persistence atomicity ────────────────────────────────────────────

(deftest write-rename-atomicity
  (testing "no .tmp files leak after register!"
    (reg/register! *worktree* base-params)
    (let [tmp-files (filter #(re-find #"\.tmp$" (str %))
                            (fs/list-dir (fs/path *worktree* ".miniforge")))]
      (is (empty? tmp-files)
          "the temp file used for write-rename should be gone after a successful write"))))

(deftest write-creates-miniforge-dir-when-missing
  (testing ".miniforge/ is created on first register"
    (let [mf-dir (fs/path *worktree* ".miniforge")]
      (is (not (fs/exists? mf-dir)) "fresh worktree has no .miniforge dir")
      (reg/register! *worktree* base-params)
      (is (fs/exists? mf-dir) ".miniforge dir created")
      (is (fs/exists? (fs/path mf-dir "listener-registry.edn"))
          "registry artifact written"))))

;; ── transition guards (terminal states are sticky) ───────────────────

(deftest unregister-rejects-non-active-entries
  (testing "unregister! returns :wrong-status on already-cancelled entries (no overwrite)"
    (let [r1 (reg/register! *worktree* base-params)
          lid (-> r1 :data :listener-id)
          _   (reg/unregister! *worktree* (:pr/url base-params) lid) ; first call: ok
          r2  (reg/unregister! *worktree* (:pr/url base-params) lid)] ; second: guard fires
      (is (not (dag/ok? r2)))
      (is (= :listener-registry/wrong-status (get-in r2 [:error :code])))
      (is (= :cancelled (get-in r2 [:error :data :current-status])))
      (is (= :active    (get-in r2 [:error :data :expected-status]))))))

(deftest unregister-rejects-dispatched-entries
  (testing "unregister! does NOT downgrade a :dispatched entry to :cancelled"
    (let [r1 (reg/register! *worktree* base-params)
          lid (-> r1 :data :listener-id)
          _   (reg/mark-dispatched! *worktree* (:pr/url base-params) lid (random-uuid))
          r2  (reg/unregister! *worktree* (:pr/url base-params) lid)]
      (is (not (dag/ok? r2)))
      (is (= :listener-registry/wrong-status (get-in r2 [:error :code])))
      (let [entries (reg/entries-for-pr (:data (reg/read-registry *worktree*))
                                        (:pr/url base-params))]
        (is (= :dispatched (:status (first entries)))
            "entry remains :dispatched, not silently overwritten")))))

(deftest mark-dispatched-rejects-non-active-entries
  (testing "mark-dispatched! refuses to dispatch a cancelled listener"
    (let [r1 (reg/register! *worktree* base-params)
          lid (-> r1 :data :listener-id)
          _   (reg/unregister! *worktree* (:pr/url base-params) lid)
          r2  (reg/mark-dispatched! *worktree* (:pr/url base-params) lid (random-uuid))]
      (is (not (dag/ok? r2)))
      (is (= :listener-registry/wrong-status (get-in r2 [:error :code])))
      (is (= :cancelled (get-in r2 [:error :data :current-status]))))))

(deftest mark-dispatched-is-not-idempotent-by-design
  (testing "double-dispatch is rejected, not silently re-stamped"
    (let [r1 (reg/register! *worktree* base-params)
          lid (-> r1 :data :listener-id)
          d1  (random-uuid)
          d2  (random-uuid)
          _   (reg/mark-dispatched! *worktree* (:pr/url base-params) lid d1)
          r2  (reg/mark-dispatched! *worktree* (:pr/url base-params) lid d2)]
      (is (not (dag/ok? r2)))
      (let [entries (reg/entries-for-pr (:data (reg/read-registry *worktree*))
                                        (:pr/url base-params))
            entry (first entries)]
        (is (= d1 (:resume/dispatch-id entry))
            "first dispatch-id sticks; second call doesn't overwrite")))))

;; ── read strictness (trailing forms + schema) ────────────────────────

(deftest read-rejects-trailing-forms
  (testing "valid registry followed by trailing data is rejected as :read-failed"
    (let [path (str (fs/path *worktree* reg/default-storage-path))]
      (fs/create-dirs (fs/parent path))
      (spit path (str (pr-str reg/empty-registry) " {:trailing :junk}")))
    (let [r (reg/read-registry *worktree*)]
      (is (not (dag/ok? r)))
      (is (= :listener-registry/read-failed (get-in r [:error :code]))))))

(deftest read-rejects-schema-mismatch
  (testing ":registry/listeners as a list (not a vector) fails malli validation on load"
    (let [path (str (fs/path *worktree* reg/default-storage-path))
          ;; bypass our normal write so we can craft a bad-but-shaped artifact
          bad  {:registry/version reg/registry-version
                :registry/last-updated (java.util.Date.)
                :registry/listeners {"https://github.com/o/r/pull/1"
                                     '({:listener/id (random-uuid)})}}]
      (fs/create-dirs (fs/parent path))
      (spit path (pr-str bad)))
    (let [r (reg/read-registry *worktree*)]
      (is (not (dag/ok? r)))
      (is (= :listener-registry/read-failed (get-in r [:error :code])))
      (is (some? (get-in r [:error :data :explain]))
          "malli explain payload is included so callers can diagnose"))))

;; ── tmp file cleanup on move failure ─────────────────────────────────

(deftest write-cleans-up-tmp-on-move-failure
  (testing "if fs/move throws, the .tmp file written by spit is best-effort-deleted"
    (let [path (str (fs/path *worktree* reg/default-storage-path))
          tmp-path (str path ".tmp")]
      (with-redefs [babashka.fs/move (fn [& _]
                                        (throw (java.lang.UnsupportedOperationException.
                                                "atomic-move not supported on this fs (simulated)")))]
        (let [r (reg/write-registry! *worktree* reg/empty-registry)]
          (is (not (dag/ok? r)))
          (is (= :listener-registry/write-failed (get-in r [:error :code])))
          (is (= tmp-path (get-in r [:error :data :tmp-path])))
          (is (= "java.lang.UnsupportedOperationException"
                 (get-in r [:error :data :exception-class])))
          (is (not (fs/exists? tmp-path))
              ".tmp file should be deleted on move failure to avoid accumulating stale artifacts"))))))
