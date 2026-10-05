(ns systems.thoughtfull.assay.dependencies.names
  "Map namespaces to Polylith interfaces."
  (:require
   [clojure.string :as str]))

(defn segment
  "The first namespace segment after top-ns, or nil if ns is not in the
  workspace."
  [top-ns ns]
  (let [prefix (str top-ns ".")
        ns (str ns)]
    (when (and top-ns (str/starts-with? ns prefix))
      (first (str/split (subs ns (count prefix)) #"\.")))))

(defn own-segment
  "The segment of most of a brick's namespaces: its interface name, for a
  component."
  [top-ns {:keys [sources]}]
  (some->> (keep #(segment top-ns (:ns %)) sources)
    frequencies
    (sort-by (comp - val))
    ffirst))

(defn interface-ns?
  "True if ns is the interface namespace of brick-segment, or one of its
  sub-interfaces."
  [{:keys [top-namespace interface-ns]} brick-segment ns]
  (let [ns (str ns)
        interface (str top-namespace "." brick-segment "." interface-ns)]
    (or (= ns interface) (str/starts-with? ns (str interface ".")))))

(defn segments
  "Brick name to its own segment, for every measurement."
  [top-ns measurements]
  (into {}
    (map (juxt (comp :name :brick) #(own-segment top-ns %)))
    measurements))
