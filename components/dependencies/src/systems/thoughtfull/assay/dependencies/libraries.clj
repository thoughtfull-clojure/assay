(ns systems.thoughtfull.assay.dependencies.libraries
  "Library spread: the bricks that require each library outside the
  workspace. A library wrapped by one brick can be replaced or upgraded in
  one place; one spread across bricks, such as a database driver, means a
  missing gateway component."
  (:require
   [clojure.string :as str]
   [systems.thoughtfull.assay.dependencies.names :as names]))

(def ^:private clojure-namespaces
  "Clojure's own pure namespaces, which every brick may use. I/O namespaces
  such as clojure.java.io and clojure.java.shell still count."
  (into #{}
    (map symbol)
    ["clojure.core" "clojure.core.protocols" "clojure.core.reducers"
     "clojure.core.server" "clojure.core.specs.alpha" "clojure.data"
     "clojure.datafy" "clojure.edn" "clojure.instant" "clojure.main"
     "clojure.math" "clojure.pprint" "clojure.reflect" "clojure.repl"
     "clojure.set" "clojure.spec.alpha" "clojure.spec.gen.alpha"
     "clojure.spec.test.alpha" "clojure.stacktrace" "clojure.string"
     "clojure.template" "clojure.test" "clojure.uuid" "clojure.walk"
     "clojure.xml" "clojure.zip"]))

(def ^:private prefix-segments
  "How many segments name a library under a shared prefix: clojure.java.io
  and clojure.tools.cli are libraries, as are babashka.fs and
  com.stuartsierra.component. Otherwise the first segment names it, as
  rewrite-clj does for rewrite-clj.node and rewrite-clj.parser."
  {"clojure" 3 "com" 3 "org" 3 "io" 3 "net" 3 "babashka" 2 "cognitect" 2})

(defn- library-key
  [ns]
  (let [segments (str/split (str ns) #"\.")]
    (str/join "." (take (prefix-segments (first segments) 1) segments))))

(defn- common-prefix
  "The longest run of namespace segments that every namespace starts with,
  so next.jdbc and next.jdbc.sql are next.jdbc."
  [namespaces]
  (let [split (map #(str/split (str %) #"\.") namespaces)]
    (->> (apply map vector split)
      (take-while #(apply = %))
      (map first)
      (str/join "."))))

(defn analyze
  "The libraries outside the workspace that each brick requires, as
  :libraries, a vector of {:library :bricks :requires} with :requires the
  first require of the library in each brick ({:brick :file :line}), most
  spread first; and :metrics, brick name to :libraries and
  :shared-libraries counts."
  [top-ns measurements]
  (let [requires (for [{:keys [brick sources]} measurements
                       {:keys [file requires]} sources
                       {:keys [ns line]} requires
                       :when (not (or (names/segment top-ns ns)
                                    (= (str ns) (str top-ns))
                                    (clojure-namespaces ns)))]
                   {:brick (:name brick) :file file :line line :ns ns})
        libraries (for [[_ rs] (group-by (comp library-key :ns) requires)
                        :let [firsts (vals (reduce (fn [acc r]
                                                     (if (acc (:brick r))
                                                       acc
                                                       (assoc acc (:brick r) r)))
                                             {}
                                             rs))]]
                    {:library (common-prefix (map :ns rs))
                     :bricks (vec (sort (map :brick firsts)))
                     :requires (vec (sort-by :brick
                                      (map #(dissoc % :ns) firsts)))})
        by-brick (group-by first (for [l libraries b (:bricks l)] [b l]))]
    {:libraries (vec (sort-by (juxt (comp - count :bricks) :library) libraries))
     :metrics (into {}
                (for [{:keys [brick]} measurements
                      :let [ls (map second (by-brick (:name brick)))]]
                  [(:name brick)
                   {:libraries (count ls)
                    :shared-libraries (count (filter #(< 1 (count (:bricks %)))
                                               ls))}]))}))

(defn spread
  "At each brick's require of a library that another brick also requires,
  the number of bricks that require it as its value. Libraries named in
  allow, as strings or symbols, are left out."
  [allow {:keys [bricks libraries]}]
  (let [index (into {} (map (juxt (comp :name :brick) :brick)) bricks)
        allowed? (set (map str allow))]
    (for [{:keys [library requires] brick-names :bricks} libraries
          :when (and (< 1 (count brick-names)) (not (allowed? library)))
          {:keys [brick file line]} requires
          :let [others (remove #{brick} brick-names)]]
      {:brick (index brick)
       :subject library
       :value (count brick-names)
       :location {:file file :line line}
       :message (str "requires " library ", which "
                  (str/join ", " others) " also "
                  (if (= 1 (count others)) "requires" "require"))})))
