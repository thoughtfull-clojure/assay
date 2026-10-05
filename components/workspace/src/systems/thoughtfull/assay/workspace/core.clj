(ns systems.thoughtfull.assay.workspace.core
  (:require
   [clojure.edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private brick-dirs
  {:component "components"
   :base "bases"})

(defn- clojure-file?
  [^java.io.File f]
  (and (.isFile f)
    (re-find #"\.clj[cs]?$" (.getName f))))

(defn- relative
  [^java.io.File root ^java.io.File f]
  (-> (.toPath (.getCanonicalFile root))
    (.relativize (.toPath (.getCanonicalFile f)))
    str
    (str/replace java.io.File/separatorChar \/)))

(defn- clojure-files
  [root dir]
  (->> (file-seq dir)
    (filter clojure-file?)
    (map #(relative root %))
    sort
    vec))

(defn- brick
  [root type ^java.io.File dir]
  {:name (.getName dir)
   :type type
   :dir (relative root dir)
   :files (clojure-files root (io/file dir "src"))
   :test-files (clojure-files root (io/file dir "test"))})

(defn bricks
  [root]
  (let [root (io/file root)]
    (vec
      (for [[type dir-name] (sort-by key brick-dirs)
            :let [parent (io/file root dir-name)]
            ^java.io.File dir (sort (.listFiles parent))
            :when (.isDirectory dir)]
        (brick root type dir)))))

(defn config
  [root]
  (let [f (io/file root "workspace.edn")
        edn (if (.exists f) (clojure.edn/read-string (slurp f)) {})]
    {:top-namespace (:top-namespace edn)
     :interface-ns (:interface-ns edn "interface")}))
