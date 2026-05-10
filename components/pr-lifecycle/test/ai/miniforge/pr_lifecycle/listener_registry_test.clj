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

(deftest sweep-expired-transitions-stale-actives
  (testing "sweep-expired! flips :active entries past TTL to :expired"
    (let [_  (reg/register! *worktree* (assoc base-params :ttl-seconds 1))
          r2 (reg/register! *worktree* (assoc base-params :agent/id "agent-B"
                                              :ttl-seconds reg/default-ttl-seconds))]
      (is (dag/ok? r2))
      (Thread/sleep 1100) ; let the agent-A entry's 1s TTL elapse
      (let [r (reg/sweep-expired! *worktree*)]
        (is (dag/ok? r))
        (is (= 1 (-> r :data :expired-count))
            "only the 1-second-TTL entry expires; the default-TTL one survives")
        (let [statuses (->> (reg/entries-for-pr
                             (:data (reg/read-registry *worktree*))
                             (:pr/url base-params))
                            (map :status)
                            set)]
          (is (= #{:expired :active} statuses)))))))

(deftest sweep-expired-is-idempotent
  (testing "running sweep twice yields 0 the second time"
    (let [_ (reg/register! *worktree* (assoc base-params :ttl-seconds 1))
          _ (Thread/sleep 1100)
          r1 (reg/sweep-expired! *worktree*)
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
