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

(ns ai.miniforge.policy-pack.standard-packs-test
  "Tests for the 11 standard reference policy packs (N4 §5).

   Validates:
   - All packs load from classpath resources
   - All packs conform to PackManifest schema
   - All packs have taxonomy-ref pointing to miniforge/dewey
   - Rule counts match spec"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.policy-pack.detection :as detection]))

(def ^:private pack-specs
  "Expected pack metadata for each reference pack."
  [{:file "foundations-1.0.0.pack.edn"     :id "miniforge/foundations"            :min-rules 4}
   {:file "terraform-aws-1.0.0.pack.edn"  :id "miniforge/terraform-aws"          :min-rules 5}
   {:file "kubernetes-1.0.0.pack.edn"      :id "miniforge/kubernetes"             :min-rules 5}
   {:file "pack-trust-1.0.0.pack.edn"     :id "miniforge/pack-trust"             :min-rules 3}
   {:file "task-scope-1.0.0.pack.edn"     :id "miniforge/task-scope"             :min-rules 5}
   {:file "capability-grant-1.0.0.pack.edn" :id "miniforge/capability-grant"     :min-rules 4}
   {:file "pack-high-risk-action-1.0.0.pack.edn" :id "miniforge/high-risk-action" :min-rules 3}
   {:file "external-pr-evaluation-1.0.0.pack.edn" :id "miniforge/external-pr-evaluation" :min-rules 3}
   {:file "opsv-governance-1.0.0.pack.edn" :id "miniforge/opsv-governance"       :min-rules 6}
   {:file "control-action-governance-1.0.0.pack.edn" :id "miniforge/control-action-governance" :min-rules 4}
   {:file "data-foundry-quality-1.0.0.pack.edn" :id "miniforge/data-foundry-quality" :min-rules 11}])

(defn- load-pack-resource [filename]
  (let [path (str "policy_pack/packs/" filename)]
    (when-let [resource (io/resource path)]
      (edn/read-string (slurp resource)))))

(deftest all-standard-packs-load-test
  (doseq [{:keys [file id min-rules]} pack-specs]
    (testing (str "Pack " file " loads and validates")
      (let [pack (load-pack-resource file)]
        (is (some? pack) (str "Resource not found: " file))
        (when pack
          (is (= id (:pack/id pack)) (str file " has correct ID"))
          (is (>= (count (:pack/rules pack)) min-rules)
              (str file " has at least " min-rules " rules"))
          (is (= :miniforge/dewey (get-in pack [:pack/taxonomy-ref :taxonomy/id]))
              (str file " references miniforge/dewey taxonomy")))))))

(deftest compiled-standards-pack-is-valid-edn-test
  ;; Regression guard for the corruption that made policy unloadable: the
  ;; compiled standards pack (a phase resource produced by `bb standards:pack`)
  ;; was serialized valid by pprint, then a text-level sanitize regex consumed
  ;; the escaping backslash of an embedded `\"/Users/…\"`, leaving an unescaped
  ;; quote. The runtime loader's edn/read-string then failed → zero rules
  ;; loaded → policy silently never enforced. Parse the shipped file directly;
  ;; edn/read-string throws (fails the test) if the pack regresses.
  (testing "components/phase/.../miniforge-standards.pack.edn round-trips through edn/read-string"
    (let [f (io/file "components/phase/resources/packs/miniforge-standards.pack.edn")]
      (is (.exists f) "compiled standards pack present (run from repo root)")
      (when (.exists f)
        (let [content (slurp f)
              pack    (edn/read-string content)]
          (is (map? pack))
          (is (pos? (count (:pack/rules pack))) "compiled pack has rules")
          (is (not (re-find #"/Users/[A-Za-z0-9._-]+/" content))
              "no real local /Users/<name>/ path leaked (doc placeholders like /Users/$USER are fine)"))))))

(deftest all-packs-have-valid-rules-test
  (doseq [{:keys [file]} pack-specs]
    (testing (str "Rules in " file " have required fields")
      (when-let [pack (load-pack-resource file)]
        (doseq [rule (:pack/rules pack)]
          (is (keyword? (:rule/id rule)) (str "Rule in " file " has keyword :rule/id"))
          (is (string? (:rule/title rule)) (str "Rule " (:rule/id rule) " has title"))
          (is (#{:critical :high :medium :low :info} (:rule/severity rule))
              (str "Rule " (:rule/id rule) " has valid severity"))
          (is (map? (:rule/detection rule)) (str "Rule " (:rule/id rule) " has detection"))
          (is (map? (:rule/enforcement rule)) (str "Rule " (:rule/id rule) " has enforcement")))))))

(deftest total-reference-rules-test
  (testing "total reference rules across all packs is at least 53"
    (let [total (->> pack-specs
                     (keep (fn [{:keys [file]}]
                             (when-let [pack (load-pack-resource file)]
                               (count (:pack/rules pack)))))
                     (reduce + 0))]
      (is (>= total 53) (str "Expected at least 53 rules, got " total)))))

;; Finding 7: no-hardcoded-secrets FP/FN. The original single regex was an FN
;; sieve (missed provider key shapes, unquoted values) and an FP generator.
;; Pin the improved patterns: high-confidence provider shapes + keyword-quoted
;; + a conservative unquoted case fire; env refs, config words, and comments do
;; not.

(defn- secrets-rule []
  (->> (load-pack-resource "foundations-1.0.0.pack.edn")
       :pack/rules
       (filter #(= :foundations/no-hardcoded-secrets (:rule/id %)))
       first))

(deftest no-hardcoded-secrets-detection-test
  (let [rule (secrets-rule)
        fires? (fn [s] (boolean (detection/detect-content-scan
                                 rule {:artifact/content s :artifact/path "x.clj"} {})))]
    (is (some? rule) "foundations pack must expose :foundations/no-hardcoded-secrets")
    (testing "real secrets fire (incl. provider key shapes with no keyword)"
      (doseq [s ["password = \"hunter2xyz\""
                 "AKIAIOSFODNN7EXAMPLE"
                 "ghp_1234567890123456789012345678901234ab"
                 "ghs_1234567890123456789012345678901234ab"
                 "sk-abcdefghijklmnopqrstuvwxyz123456"
                 "-----BEGIN RSA PRIVATE KEY-----"
                 "password: aGVsbG8gd29ybGRzZWNyZXQ="
                 "SECRET=aGVsbG8gd29ybGRzZWNyZXQ="
                 "api_key: \"abcdefgh12345678\""
                 "client_secret = \"s3cr3tvalue99\""]]
        (is (fires? s) (str "should fire: " s))))
    (testing "non-secrets do not fire (env refs, function calls, config, comments)"
      (doseq [s ["password = env(\"SECRET\")"
                 "password: required"
                 "let token = getToken()"
                 "apikey = getApiKeyFromVault()"
                 "# password: use a secrets manager"
                 "password_field: user_name_column"
                 "(def x 42)"]]
        (is (not (fires? s)) (str "should NOT fire: " s))))))

;; Finding 7: no-inline-anon-fns missed the reader `#(...)` form; no-latest-tag
;; missed untagged images (implicitly :latest) and false-fired on :latest-*.

(defn- pack-rule [file id]
  (->> (load-pack-resource file) :pack/rules (filter #(= id (:rule/id %))) first))

(deftest no-inline-anon-fns-detection-test
  (let [rule (pack-rule "foundations-1.0.0.pack.edn" :foundations/no-inline-anon-fns)
        fires? (fn [s] (boolean (detection/detect-content-scan rule {:artifact/content s} {})))]
    (is (some? rule))
    (is (fires? "(map (fn [x] (inc x)) coll)") "explicit (fn [..])")
    (is (fires? "(map #(inc %) coll)") "reader #(..) — the missed form")
    (is (not (fires? "(map inc coll)")) "named fn is fine")
    (is (not (fires? "(filter even? coll)")) "named predicate is fine")))

(deftest no-latest-tag-detection-test
  (let [rule (pack-rule "kubernetes-1.0.0.pack.edn" :k8s/no-latest-tag)
        fires? (fn [s] (boolean (detection/detect-content-scan rule {:artifact/content s} {})))]
    (is (some? rule))
    (testing "explicit :latest and untagged images fire"
      (is (fires? "image: nginx:latest"))
      (is (fires? "image: \"nginx:latest\""))
      (is (fires? "image: nginx") "untagged is implicitly :latest")
      (is (fires? "image: myreg:5000/app") "registry-port but untagged"))
    (testing "pinned tags, digests, and :latest-* do not fire"
      (is (not (fires? "image: nginx:1.2.3")))
      (is (not (fires? "image: app@sha256:abcdef123")))
      (is (not (fires? "image: nginx:latest-stable")) "latest-stable is a pinned tag"))))
