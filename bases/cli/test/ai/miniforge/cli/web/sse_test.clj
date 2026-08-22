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
(ns ai.miniforge.cli.web.sse-test
  "Regression coverage for the on-open/on-close subscription bookkeeping
   bug found in PR review: `streams` holds event-stream atoms (not
   maps), so tracking per-channel subscriber ids via assoc-in/update-in
   directly on a `streams` entry throws ClassCastException. Fixed by
   tracking channel->sub-id in a separate `subscriptions` atom. The
   registry atoms/CRUD live in `ai.miniforge.cli.web.sse.registry` (rule
   210 split); `on-open` itself stays in `ai.miniforge.cli.web.sse`."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [org.httpkit.server :as http]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.web.sse :as sse]
   [ai.miniforge.cli.web.sse.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (reset! registry/streams {})
    (reset! registry/subscriptions {})
    (f)
    (reset! registry/streams {})
    (reset! registry/subscriptions {})))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} on-open-registers-stream-and-subscription-without-throwing-test
  (testing "the first SSE connect for a workflow-id no longer throws ClassCastException"
    (with-redefs [http/send! (constantly true)]
      (is (uuid? (sse/on-open "wf-1" :channel-a))
          "on-open returns the subscriber id without throwing")
      (is (some? (registry/get-stream "wf-1"))
          "get-or-create-stream should have registered the event stream")
      (is (= 1 (count (get @registry/subscriptions "wf-1")))
          (str "subscriptions should be tracked for wf-1, got: " @registry/subscriptions)))))

(deftest ^{:stratum 0} on-open-supports-multiple-channels-for-the-same-workflow-test
  (testing "two browser tabs watching the same workflow each get their own subscription"
    (with-redefs [http/send! (constantly true)]
      (sse/on-open "wf-2" :channel-a)
      (sse/on-open "wf-2" :channel-b)
      (is (= #{:channel-a :channel-b} (set (keys (get @registry/subscriptions "wf-2")))))
      (is (= (registry/get-stream "wf-2") (registry/get-stream "wf-2"))
          "both channels share the same underlying event stream for the workflow"))))

(deftest ^{:stratum 0} on-close-removes-only-its-own-channel-subscription-test
  (testing "closing one channel doesn't disturb the stream or a sibling channel's subscription"
    (with-redefs [http/send! (constantly true)]
      (sse/on-open "wf-3" :channel-a)
      (sse/on-open "wf-3" :channel-b)
      (registry/on-close "wf-3" :channel-a)
      (is (= #{:channel-b} (set (keys (get @registry/subscriptions "wf-3")))))
      (is (some? (registry/get-stream "wf-3"))
          "the event stream itself must survive a single channel closing"))))

(deftest ^{:stratum 0} on-close-drops-the-workflow-key-once-its-last-channel-closes-test
  (testing "no empty {workflow-id {}} entry is left behind in subscriptions"
    (with-redefs [http/send! (constantly true)]
      (sse/on-open "wf-6" :channel-a)
      (registry/on-close "wf-6" :channel-a)
      (is (not (contains? @registry/subscriptions "wf-6"))
          (str "expected wf-6 to be fully removed from subscriptions, got: " @registry/subscriptions))
      (is (some? (registry/get-stream "wf-6"))
          "the event stream itself is unaffected — only subscription bookkeeping is pruned"))))

(deftest ^{:stratum 0} on-close-is-a-no-op-for-an-unknown-channel-test
  (testing "closing a channel that was never opened doesn't throw"
    (is (nil? (registry/on-close "wf-4" :never-opened)))))

(deftest ^{:stratum 0} unregister-clears-both-streams-and-subscriptions-test
  (testing "unregister! clears subscription bookkeeping alongside the stream itself"
    (with-redefs [http/send! (constantly true)]
      (sse/on-open "wf-5" :channel-a)
      (registry/unregister! "wf-5")
      (is (nil? (registry/get-stream "wf-5")))
      (is (nil? (get @registry/subscriptions "wf-5"))))))

(deftest ^{:stratum 0} unregister-unsubscribes-still-open-channels-before-dropping-state-test
  (testing "channels never closed via on-close still get es/unsubscribe! called on unregister!"
    (with-redefs [http/send! (constantly true)]
      (sse/on-open "wf-7" :channel-a)
      (sse/on-open "wf-7" :channel-b)
      (let [unsubscribed (atom #{})]
        (with-redefs [es/unsubscribe! (fn [_stream sub-id] (swap! unsubscribed conj sub-id) nil)]
          (let [expected-sub-ids (set (vals (get @registry/subscriptions "wf-7")))]
            (registry/unregister! "wf-7")
            (is (= expected-sub-ids @unsubscribed)
                "both still-open channels' subscriber ids must be unsubscribed, not silently orphaned")))))))
