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
(ns ai.miniforge.governance-provenance.core-test
  (:require
   [ai.miniforge.governance-provenance.core :as core]
   [ai.miniforge.governance-provenance.interface :as provenance]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} revision "1111111111111111111111111111111111111111")

(def ^{:stratum 0} blob "2222222222222222222222222222222222222222")

(def ^{:stratum 0} commit "3333333333333333333333333333333333333333")

(def ^{:stratum 0} merge-commit "4444444444444444444444444444444444444444")

(def ^{:stratum 0} separator "\u001f")

(defn ^{:stratum 0} request
  [location]
  {:repository {:repository/root "/repo" :repository/revision "main"}
   :incident {:incident/id "INC-7" :incident/source :user-report}
   :locations [location]})

(def ^{:stratum 0} base-location
  {:path "components/example.clj" :start-line 8 :end-line 10})

(def ^{:stratum 0} governance-input
  {:specification-mappings
   [{:mapping/id "n1-repository-intelligence"
     :mapping/file-globs ["components/**"]
     :specification/id "N1"
     :specification/revision "0.8.0-draft"
     :specification/path "specs/normative/N1-architecture.md"
     :specification/clause "2.27"}
    {:mapping/id "n1-symbol-identity"
     :mapping/file-globs ["components/**"]
     :specification/id "N1" :specification/revision "0.8.0-draft"
     :specification/path "specs/normative/N1-architecture.md"
     :specification/clause "2.28"}]
   :policy-rules
   [{:rule/id :architecture/stratified-design
     :rule/revision "pack-sha"
     :rule/enabled? true
     :rule/applies-to {:file-globs ["components/**"]}}]})

(deftest ^{:stratum 0} mapping-and-rule-matching-normalizes-windows-separators
  (let [win-path "components\\example\\core.clj"
        mapping {:mapping/file-globs ["components/**"]}
        rule {:rule/id :architecture/stratified-design
              :rule/enabled? true
              :rule/applies-to {:file-globs ["components/**"]}}]
    (is (core/mapping-matches? win-path mapping))
    (is (= [rule] (core/applicable-rules win-path [rule] {})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} fake-git
  "`ls-tree-fails?` reproduces the case where a commit is pinned by
   `blame` but its blob (and so its range) can't be resolved."
  ([merge? calls] (fake-git merge? calls false))
  ([merge? calls ls-tree-fails?]
   (fn [_repo-root args]
     (swap! calls conj args)
     (case (second args)
       "rev-parse" {:success? true :output revision}
       "ls-tree" (if ls-tree-fails?
                   {:success? false :error "ls-tree failed"}
                   {:success? true :output (str "100644 blob " blob "\tcomponents/example.clj")})
       "blame" {:success? true :output (str commit " 8 8 3")}
       "show" {:success? true
               :output (str commit separator "2026-08-01T12:00:00Z" separator
                            "Ada" separator "Add the implicated behavior")}
       "log" {:success? true
              :output (if merge?
                        (str merge-commit separator "Merge pull request #42 from example/feature")
                        "")}
       {:success? false :error (str "unexpected command " args)}))))

(deftest ^{:stratum 1} fails-closed-when-revision-cannot-be-pinned
  (let [result (provenance/build-dossier
                (request base-location)
                {:git-exec (fn [_ _] {:success? false :error "unknown revision"})})]
    (is (false? (:success? result)))
    (is (= :repository-revision-unavailable (get-in result [:error :error/type])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} builds-complete-read-only-incident-projection
  (let [calls (atom [])
        location (assoc base-location :symbol {:symbol/id "local:clj:example:fn:run"
                                                :symbol/name "run"
                                                :symbol/fqname "example/run"
                                                :symbol/kind :fn})
        result (provenance/build-dossier
                (merge (assoc (request location) :locations
                              [location (assoc location :start-line 11 :end-line 12)])
                       governance-input)
                {:git-exec (fake-git true calls)
                 :clock #(java.time.Instant/parse "2026-08-05T00:00:00Z")})
        node-types (set (map :node/type (:dossier/nodes result)))
        edge-types (set (map :edge/type (:dossier/edges result)))
        governance-refs (->> (:dossier/edges result)
                             (filter #(= :governed-by (:edge/type %)))
                             (mapcat :edge/basis)
                             (map :evidence/ref)
                             set)
        path (first (:dossier/paths result))]
    (is (:success? result))
    (is (= :complete (:dossier/status result)))
    (is (= #{:incident :symbol-revision :range :commit :pull-request
             :specification-revision :policy-rule-revision :evidence :generated-claim}
           node-types))
    (is (= #{:implicates :cites :changed-by :contained-in :governed-by :supports}
           edge-types))
    (is (not (contains? edge-types :introduced-by)))
    (is (= "incident:INC-7" (first (:path/nodes path))))
    (is (= "pull-request:42" (last (:path/nodes path))))
    (is (= :provided (get-in result [:dossier/coverage :coverage/symbols :status])))
    (is (= 1 (get-in result [:dossier/coverage :coverage/pull-requests :resolved])))
    (is (= #{"n1-repository-intelligence" "n1-symbol-identity"
             ":architecture/stratified-design"}
           governance-refs))
    (is (= 2 (count (filter #(= :evidence (:node/type %)) (:dossier/nodes result)))))
    (is (= 2 (count (:edge/basis (first (filter #(= :changed-by (:edge/type %))
                                                (:dossier/edges result)))))))
    (is (= [] (:dossier/gaps result)))
    (is (seq @calls))))

(deftest ^{:stratum 2} reports-coverage-gaps-without-inventing-symbols-or-prs
  (let [result (provenance/build-dossier
                (request base-location)
                {:git-exec (fake-git false (atom []))})
        gaps (set (map :gap/type (:dossier/gaps result)))
        edge-types (set (map :edge/type (:dossier/edges result)))]
    (is (:success? result))
    (is (= :partial (:dossier/status result)))
    (is (= :none (get-in result [:dossier/coverage :coverage/symbols :status])))
    (is (every? gaps [:symbol-resolution-unavailable
                      :specification-mappings-not-provided
                      :policy-rules-not-provided
                      :pull-request-unresolved]))
    (is (some #(= :range (:node/type %)) (:dossier/nodes result)))
    (is (not (contains? edge-types :introduced-by)))))

(deftest ^{:stratum 2} rejects-unsafe-locations-before-running-git
  (let [calls (atom [])
        result (provenance/build-dossier
                (request {:path "../secret" :start-line 1 :end-line 1})
                {:git-exec (fake-git true calls)})]
    (is (false? (:success? result)))
    (is (= :invalid-incident-dossier-request (get-in result [:error :error/type])))
    (is (empty? @calls))))

(deftest ^{:stratum 2} rejects-unidentified-governance-inputs-before-running-git
  (let [calls (atom [])
        inputs (assoc governance-input :specification-mappings
                      [(dissoc (first (:specification-mappings governance-input)) :mapping/id)])
        result (provenance/build-dossier
                (merge (request base-location) inputs)
                {:git-exec (fake-git true calls)})]
    (is (false? (:success? result)))
    (is (= :invalid-incident-dossier-request (get-in result [:error :error/type])))
    (is (empty? @calls))))

(deftest ^{:stratum 2} unversioned-governance-inputs-remain-explicit-gaps
  (let [location (assoc base-location :symbol {:symbol/id "symbol:run"})
        inputs (-> governance-input
                   (update :specification-mappings #(mapv (fn [m] (dissoc m :specification/revision)) %))
                   (update :policy-rules #(mapv (fn [r] (dissoc r :rule/revision)) %)))
        result (provenance/build-dossier
                (merge (request location) inputs)
                {:git-exec (fake-git true (atom []))})
        gaps (set (map :gap/type (:dossier/gaps result)))]
    (is (= :partial (:dossier/status result)))
    (is (contains? gaps :specification-revision-unavailable))
    (is (contains? gaps :policy-rule-revision-unavailable))))

(deftest ^{:stratum 2} blame-evidence-marks-unresolved-range-explicitly
  (let [location (assoc base-location :symbol {:symbol/id "local:clj:example:fn:run"})
        result (provenance/build-dossier
                (request location)
                {:git-exec (fake-git true (atom []) true)})
        gaps (set (map :gap/type (:dossier/gaps result)))
        evidence (first (filter #(= :evidence (:node/type %)) (:dossier/nodes result)))]
    (is (:success? result))
    (is (contains? gaps :immutable-range-unavailable))
    (is (some? evidence))
    (is (false? (get-in evidence [:node/content :evidence/range-resolved?])))
    (is (not (contains? (:node/content evidence) :evidence/range)))
    (is (not (re-find #":nil:" (get-in evidence [:node/content :evidence/id]))))
    (is (re-find #":unresolved-range:" (get-in evidence [:node/content :evidence/id])))))
