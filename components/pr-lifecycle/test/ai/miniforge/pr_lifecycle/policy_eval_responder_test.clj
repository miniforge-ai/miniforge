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

(ns ai.miniforge.pr-lifecycle.policy-eval-responder-test
  "Tests for the N13 §2.5 Comment Response Agent (deterministic
   policy-eval path)."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.compliance-scanner.interface :as scanner]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pr-lifecycle.github :as github]
            [ai.miniforge.pr-lifecycle.policy-eval-responder :as sut]))

;; ── fixture: ephemeral worktree ──────────────────────────────────────

(def ^:dynamic *worktree* nil)

(defn worktree-fixture [f]
  (let [w (str (fs/create-temp-dir {:prefix "policy-eval-responder-test-"}))]
    (try
      (binding [*worktree* w] (f))
      (finally (try (fs/delete-tree w) (catch Throwable _ nil))))))

(use-fixtures :each worktree-fixture)

;; ── helpers ──────────────────────────────────────────────────────────

(defn- write-file!
  "Write `content` to `*worktree*/relative-path`, creating dirs."
  [relative-path content]
  (let [abs (str (fs/path *worktree* relative-path))]
    (fs/create-dirs (fs/parent abs))
    (spit abs content)))

(defn- read-file
  [relative-path]
  (slurp (str (fs/path *worktree* relative-path))))

(def ^:private fixable-payload
  {:violation/rule-id        :ai.miniforge.standards/exceptions-as-data
   :violation/severity       :warning
   :violation/auto-fixable?  true
   :violation/suggested-fix  "(anomaly/throw-anomaly :foo/bad-state {})"
   :violation/rationale      "Throwing exceptions breaks effect-as-data."
   :violation/pack-id        "miniforge-standards"
   :violation/pack-version   "1.4.0"})

(def ^:private non-fixable-payload
  (assoc fixable-payload
         :violation/auto-fixable? false
         :violation/suggested-fix nil
         :violation/rule-id :ai.miniforge.standards/no-credentials-in-source))

(defn- comment-with
  "Build a comment record carrying `payload` rendered into the body."
  [path line payload & {:keys [author id]
                        :or {author sut/policy-eval-author
                             id (rand-int 1000000)}}]
  (let [body (-> (scanner/violation->comment
                  ;; minimal violation needed for the renderer
                  {:rule/id        (:violation/rule-id payload)
                   :rule/category  "test"
                   :rule/title     "test"
                   :file           path
                   :line           line
                   :current        ""
                   :suggested      (:violation/suggested-fix payload)
                   :auto-fixable?  (:violation/auto-fixable? payload)
                   :rationale      (:violation/rationale payload)}
                  {:pack/id      (:violation/pack-id payload)
                   :pack/version (:violation/pack-version payload)})
                 :comment/body)]
    {:comment/id     id
     :comment/author author
     :comment/path   path
     :comment/line   line
     :comment/body   body}))

;; ── classify-fix ─────────────────────────────────────────────────────

(deftest classify-fix-apply-on-fixable-single-line
  (let [c (comment-with "src/foo.clj" 5 fixable-payload)
        r (sut/classify-fix c)]
    (is (= :apply (:action r)))
    (is (= :ok (:reason r)))
    (is (some? (:payload r)))))

(deftest classify-fix-escalate-on-not-fixable
  (let [c (comment-with "src/sec.clj" 7 non-fixable-payload)
        r (sut/classify-fix c)]
    (is (= :escalate (:action r)))
    (is (= :violation/auto-fixable?-false (:reason r)))))

(deftest classify-fix-escalate-on-multi-line-suggestion
  (let [p (assoc fixable-payload
                 :violation/suggested-fix "line one\nline two\nline three")
        c (comment-with "src/foo.clj" 5 p)
        r (sut/classify-fix c)]
    (is (= :escalate (:action r)))
    (is (= :policy-eval/multi-line-not-supported (:reason r)))))

(deftest classify-fix-escalate-on-blank-suggestion
  (let [p (assoc fixable-payload :violation/suggested-fix "")
        c (comment-with "src/foo.clj" 5 p)
        r (sut/classify-fix c)]
    (is (= :escalate (:action r)))
    (is (= :no-suggested-fix (:reason r)))))

(deftest classify-fix-skip-on-no-payload
  (let [c {:comment/id 1 :comment/author sut/policy-eval-author
           :comment/path "src/x.clj" :comment/line 1
           :comment/body "no edn payload here"}
        r (sut/classify-fix c)]
    (is (= :skip (:action r)))
    (is (= :no-payload (:reason r)))))

;; ── plan-fixes (partition) ───────────────────────────────────────────

(deftest plan-fixes-partitions-correctly
  (let [a (comment-with "src/a.clj" 1 fixable-payload :id 100)
        b (comment-with "src/b.clj" 2 non-fixable-payload :id 101)
        c (comment-with "src/c.clj" 3 fixable-payload :id 102)
        plan (sut/plan-fixes [a b c])]
    (is (= 2 (count (:to-apply plan))))
    (is (= #{100 102} (set (map (comp :comment/id :comment) (:to-apply plan)))))
    (is (= 1 (count (:to-escalate plan))))
    (is (= 101 (-> plan :to-escalate first :comment :comment/id)))
    (is (empty? (:to-skip plan)))))

;; ── apply-single-line-replacement! ───────────────────────────────────

(deftest apply-single-line-replacement-success
  (write-file! "src/foo.clj" "(ns foo)\n(throw (ex-info \"x\" {}))\n(println :ok)\n")
  (let [r (sut/apply-single-line-replacement!
           *worktree* "src/foo.clj" 2
           "(anomaly/throw-anomaly :foo/x {})")]
    (is (dag/ok? r))
    (is (= "(throw (ex-info \"x\" {}))" (-> r :data :before)))
    (is (= "(anomaly/throw-anomaly :foo/x {})" (-> r :data :after)))
    (is (str/includes? (read-file "src/foo.clj")
                       "(anomaly/throw-anomaly :foo/x {})"))
    (is (not (str/includes? (read-file "src/foo.clj")
                            "(throw (ex-info \"x\" {}))")))))

(deftest apply-single-line-replacement-rejects-missing-file
  (let [r (sut/apply-single-line-replacement!
           *worktree* "no/such.clj" 1 "(replacement)")]
    (is (not (dag/ok? r)))
    (is (= :policy-eval/file-not-found (get-in r [:error :code])))))

(deftest apply-single-line-replacement-rejects-out-of-range-line
  (write-file! "src/short.clj" "line1\nline2\n")
  (let [r (sut/apply-single-line-replacement!
           *worktree* "src/short.clj" 999 "(x)")]
    (is (not (dag/ok? r)))
    (is (= :policy-eval/line-out-of-range (get-in r [:error :code])))
    (is (= 999 (get-in r [:error :data :line])))))

;; ── materialize-fix! decorates with comment-id ───────────────────────

(deftest materialize-fix-decorates-with-comment-id-on-success
  (write-file! "src/foo.clj" "line1\n(throw (ex-info \"x\" {}))\nline3\n")
  (let [c (comment-with "src/foo.clj" 2 fixable-payload :id 999)
        entry {:comment c :payload fixable-payload}
        r (sut/materialize-fix! *worktree* entry)]
    (is (dag/ok? r))
    (is (= 999 (-> r :data :comment/id)))))

(deftest materialize-fix-decorates-with-comment-id-on-failure
  (let [c (comment-with "no/such.clj" 1 fixable-payload :id 42)
        entry {:comment c :payload fixable-payload}
        r (sut/materialize-fix! *worktree* entry)]
    (is (not (dag/ok? r)))
    (is (= 42 (get-in r [:error :data :comment/id])))))

;; ── respond-to-policy-comments! end-to-end with mocks ────────────────

(deftest respond-end-to-end-only-policy-eval-comments-considered
  (testing "comments from non-policy-eval authors are ignored entirely"
    (write-file! "src/foo.clj" "ok\n")
    (let [human-comment {:comment/id 1
                         :comment/author "alice"
                         :comment/path "src/foo.clj"
                         :comment/line 1
                         :comment/body "looks good"}
          r (sut/respond-to-policy-comments! *worktree* 42 [human-comment])]
      (is (dag/ok? r))
      (is (empty? (-> r :data :applied)))
      (is (empty? (-> r :data :escalated)))
      (is (empty? (-> r :data :skipped))
          "human comments are filtered out before plan-fixes; never enter the buckets"))))

(deftest respond-end-to-end-applies-fixes-and-replies
  (testing "applies fixable comments → commits → pushes → replies + resolves"
    (write-file! "src/foo.clj" "(ns foo)\n(throw (ex-info \"x\" {}))\n(println :ok)\n")
    (write-file! "src/bar.clj" "(println :before)\n")
    (let [git-calls (atom [])
          gh-calls  (atom [])
          fix-c1 (comment-with "src/foo.clj" 2 fixable-payload :id 111)
          fix-c2 (comment-with "src/bar.clj" 1 (assoc fixable-payload
                                                      :violation/suggested-fix
                                                      "(println :after)") :id 222)
          stub-process (fn [_opts & args]
                         (swap! git-calls conj args)
                         (cond
                           (some #{"rev-parse"} args)
                           {:exit 0 :out "abc1234deadbeef\n" :err ""}
                           :else
                           {:exit 0 :out "" :err ""}))]
      (with-redefs [process/shell stub-process
                    github/reply-to-comment
                    (fn [_ _ cid _]
                      (swap! gh-calls conj [:reply cid])
                      (dag/ok {:reply-id (rand-int 1000) :url "https://x" :body "ok"}))
                    github/get-thread-id
                    (fn [_ _ cid]
                      (swap! gh-calls conj [:thread cid])
                      (dag/ok {:thread-id "PRRT_FAKE" :is-resolved false}))
                    github/resolve-conversation
                    (fn [_ tid]
                      (swap! gh-calls conj [:resolve tid])
                      (dag/ok {:thread-id tid :resolved true}))]
        (let [r (sut/respond-to-policy-comments! *worktree* 42 [fix-c1 fix-c2])
              data (:data r)]
          (is (dag/ok? r))
          (is (= 2 (count (:applied data))))
          (is (= "abc1234deadbeef" (:commit-sha data)))
          (is (true? (:pushed? data)))
          (is (= 2 (count (:replies data))))
          ;; verify each reply was actually delivered + resolved
          (is (= 2 (count (filter #(= :reply (first %)) @gh-calls))))
          (is (= 2 (count (filter #(= :resolve (first %)) @gh-calls))))
          ;; verify file mutations actually happened
          (is (str/includes? (read-file "src/foo.clj")
                             (:violation/suggested-fix fixable-payload)))
          (is (str/includes? (read-file "src/bar.clj")
                             "(println :after)")))))))

(deftest respond-end-to-end-escalates-non-fixable
  (testing "non-fixable comments end up in :escalated, not :applied"
    (write-file! "src/sec.clj" "ok\n")
    (let [c (comment-with "src/sec.clj" 1 non-fixable-payload :id 333)
          r (sut/respond-to-policy-comments! *worktree* 42 [c])
          data (:data r)]
      (is (dag/ok? r))
      (is (empty? (:applied data)))
      (is (= 1 (count (:escalated data))))
      (is (= 333 (-> data :escalated first :comment :comment/id)))
      (is (= :violation/auto-fixable?-false (-> data :escalated first :reason)))
      (is (nil? (:commit-sha data))
          "no commit because nothing was applied"))))

(deftest respond-end-to-end-no-commit-when-zero-fixes
  (testing "all-escalated-no-applies path doesn't try to commit/push"
    (let [stub (fn [_ & args] (throw (ex-info "should not be called" {:args args})))]
      (with-redefs [process/shell stub]
        (let [c (comment-with "src/x.clj" 1 non-fixable-payload :id 1)
              r (sut/respond-to-policy-comments! *worktree* 42 [c])]
          (is (dag/ok? r))
          (is (false? (-> r :data :pushed?))
              "pushed? is false when nothing applied — no git operations"))))))
