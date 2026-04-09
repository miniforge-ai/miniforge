;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.cli.repo-analyzer
  "Analyze a repository to detect languages, frameworks, and build systems.

   Layer 0: File extension counting and detection heuristics
   Layer 1: Framework and build system detection
   Layer 2: Pack selection based on detected characteristics"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Language detection

(def ^:private extension->language
  "Map file extensions to language keywords."
  {"clj"  :clojure   "cljs" :clojure   "cljc" :clojure  "edn" :clojure
   "py"   :python    "pyi"  :python
   "js"   :javascript "mjs" :javascript "cjs" :javascript
   "ts"   :typescript "tsx" :typescript
   "rs"   :rust
   "go"   :go
   "java" :java
   "rb"   :ruby
   "swift" :swift
   "tf"   :terraform "tfvars" :terraform
   "yaml" :yaml      "yml" :yaml
   "json" :json
   "sh"   :bash      "bash" :bash
   "md"   :markdown})

(defn- file-extension [path]
  (let [name (str (fs/file-name path))]
    (when-let [idx (str/last-index-of name ".")]
      (subs name (inc idx)))))

(defn detect-languages
  "Detect languages present in a repository by scanning file extensions.
   Returns a set of language keywords."
  [repo-path]
  (let [files (fs/glob repo-path "**" {:hidden false})]
    (->> files
         (keep (fn [f]
                 (when (fs/regular-file? f)
                   (get extension->language (file-extension (str f))))))
         (into #{}))))

;------------------------------------------------------------------------------ Layer 1
;; Framework and build system detection

(def ^:private framework-markers
  "File/directory markers that indicate a framework."
  [{:marker "components"     :test #(fs/directory? %) :framework :polylith}
   {:marker "workspace.edn"  :test #(fs/exists? %)    :framework :polylith}
   {:marker "Dockerfile"     :test #(fs/exists? %)    :framework :docker}
   {:marker "docker-compose.yml" :test #(fs/exists? %) :framework :docker-compose}
   {:marker "Chart.yaml"     :test #(fs/exists? %)    :framework :helm}
   {:marker "kustomization.yaml" :test #(fs/exists? %) :framework :kustomize}])

(def ^:private build-markers
  "File markers for build systems."
  [{:marker "deps.edn"       :build :deps-edn}
   {:marker "project.clj"    :build :leiningen}
   {:marker "bb.edn"         :build :babashka}
   {:marker "package.json"   :build :npm}
   {:marker "Cargo.toml"     :build :cargo}
   {:marker "go.mod"         :build :go-mod}
   {:marker "pom.xml"        :build :maven}
   {:marker "build.gradle"   :build :gradle}
   {:marker "Makefile"       :build :make}
   {:marker "pyproject.toml" :build :pyproject}
   {:marker "setup.py"       :build :setuptools}
   {:marker "Gemfile"        :build :bundler}])

(defn- detect-by-markers [repo-path markers key-field]
  (->> markers
       (filter (fn [{:keys [marker test] :or {test fs/exists?}}]
                 (test (fs/path repo-path marker))))
       (map key-field)
       (into #{})))

(defn detect-frameworks
  "Detect frameworks used in a repository."
  [repo-path]
  (let [base (detect-by-markers repo-path framework-markers :framework)]
    ;; Check for K8s manifests (apiVersion in YAML files)
    (if (some (fn [f]
                (when (and (fs/regular-file? f)
                           (re-matches #".*\.ya?ml$" (str f)))
                  (try
                    (str/includes? (slurp (str f)) "apiVersion:")
                    (catch Exception _ false))))
              (fs/glob repo-path "**/*.{yaml,yml}" {:hidden false}))
      (conj base :kubernetes)
      base)))

(defn detect-build-systems
  "Detect build systems used in a repository."
  [repo-path]
  (detect-by-markers repo-path build-markers :build))

(defn detect-git-host
  "Detect git host from remote URL."
  [repo-path]
  (try
    (let [result (clojure.java.shell/sh "git" "remote" "get-url" "origin" :dir (str repo-path))
          url    (:out result)]
      (cond
        (str/includes? url "github.com")    :github
        (str/includes? url "gitlab")        :gitlab
        (str/includes? url "bitbucket.org") :bitbucket
        (not (str/blank? url))              :custom
        :else                               nil))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2
;; Pack selection

(def ^:private language->packs
  "Map languages to applicable reference packs."
  {:clojure    []
   :terraform  ["terraform-aws-1.0.0"]
   :python     []
   :javascript []
   :typescript []})

(def ^:private framework->packs
  "Map frameworks to applicable reference packs."
  {:kubernetes     ["kubernetes-1.0.0"]
   :helm           ["kubernetes-1.0.0"]
   :kustomize      ["kubernetes-1.0.0"]
   :docker         []
   :polylith       []})

(def ^:private always-packs
  "Packs that always apply."
  ["foundations-1.0.0"])

(defn select-packs
  "Select applicable policy packs based on detected repo characteristics."
  [languages frameworks]
  (let [lang-packs      (mapcat #(get language->packs % []) languages)
        framework-packs (mapcat #(get framework->packs % []) frameworks)]
    (vec (distinct (concat always-packs lang-packs framework-packs)))))

;------------------------------------------------------------------------------ Layer 2
;; Full analysis

(defn analyze-repo
  "Analyze a repository and return its characteristics.
   Returns {:languages #{} :frameworks #{} :build-systems #{}
            :git-host keyword? :packs [string?]}"
  [repo-path]
  (let [languages    (detect-languages repo-path)
        frameworks   (detect-frameworks repo-path)
        build-systems (detect-build-systems repo-path)
        git-host     (detect-git-host repo-path)
        packs        (select-packs languages frameworks)]
    {:languages    languages
     :frameworks   frameworks
     :build-systems build-systems
     :git-host     git-host
     :packs        packs}))
