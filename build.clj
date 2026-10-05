(ns build
  "Build and deploy the assay library from the Polylith project
  projects/assay. Follows systems.thoughtfull/build: build.edn for the lib,
  version, and description, and ~/.clojars.edn for Clojars credentials.
  That library expects a single src directory, so this one gathers the
  sources of every brick in the project instead.

  clojure -T:build jar     ; target/assay-<version>.jar
  clojure -T:build install ; into ~/.m2
  clojure -T:build deploy  ; to Clojars"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def build-config
  (edn/read-string (slurp "build.edn")))

(def lib (:lib build-config))
(def version (:version build-config))
(def description (:description build-config))
(def repo (str "thoughtfull-clojure/" (name lib)))
(def project-dir "projects/assay")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(def pom-data
  [[:description description]
   [:url (str "https://github.com/" repo)]
   [:licenses
    [:license
     [:name "Mozilla Public License Version 2.0"]
     [:url "http://mozilla.org/MPL/2.0/"]]]
   [:developers
    [:developer
     [:name "technosophist"]]]
   [:scm
    [:url (str "https://github.com/" repo)]
    [:connection (str "scm:git:git://github.com/" repo ".git")]
    [:developerConnection (str "scm:git:ssh://git@github.com:" repo ".git")]
    [:tag (str "v" version)]]])

(defn- project-basis
  "The basis of projects/assay, whose bricks are :local/root dependencies."
  []
  (binding [b/*project-root* project-dir]
    (b/create-basis {:project "deps.edn"})))

(defn- source-dirs
  "Every brick's source directory, relative to the workspace root."
  [basis]
  (->> (:classpath-roots basis)
    (remove #(str/ends-with? % ".jar"))
    (map #(let [f (io/file %)]
            (if (.isAbsolute f) f (io/file project-dir %))))
    (filter #(.isDirectory ^java.io.File %))
    (map #(str (.relativize (.toPath (.getCanonicalFile (io/file ".")))
                 (.toPath (.getCanonicalFile ^java.io.File %)))))
    sort
    vec))

(defn- pom-basis
  "A basis for write-pom whose top-level libraries are the project's Maven
  dependencies and its bricks'. write-pom keeps only top-level libraries
  and skips :local/root ones, which would leave out the bricks' own
  dependencies."
  [{:keys [libs]}]
  (let [local? #(contains? (get libs %) :local/root)]
    {:libs (into {}
             (for [[l coord] libs
                   :when (and (:mvn/version coord)
                           (every? local? (:dependents coord)))]
               [l (dissoc coord :dependents)]))}))

(defn- validate!
  []
  (assert (qualified-symbol? lib))
  (assert (re-matches #"\d+\.\d+\.\d+(?:-\w+)?" version))
  (assert (seq description)))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  [_]
  (validate!)
  (clean nil)
  (let [basis (project-basis)
        src-dirs (source-dirs basis)]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis (pom-basis basis)
                  :pom-data pom-data})
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Wrote" jar-file)))

(defn install
  [_]
  (jar nil)
  (dd/deploy {:installer :local
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))

(defn- clojars-credentials
  []
  (let [f (io/file (System/getProperty "user.home") ".clojars.edn")]
    (when (.exists f)
      (select-keys (edn/read-string (slurp f)) [:username :password]))))

(defn deploy
  [_]
  (let [{:keys [username password]} (clojars-credentials)]
    (assert (and username password)
      "Clojars credentials in ~/.clojars.edn: {:username ... :password ...}")
    (jar nil)
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path {:lib lib :class-dir class-dir})
                :repository {"clojars" {:url "https://clojars.org/repo"
                                        :username username
                                        :password password}}})))
