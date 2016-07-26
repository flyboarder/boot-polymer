(ns degree9.boot-polymer.impl
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [boot.util :as util]
            [hickory.core :as h]
            [dickory-dock.core :as d]
            [hickory.select :as hsel]
            [dickory-dock.select :as dsel]
            [clojure.zip :as zip]
            [hickory.zip :as hzip]
            [dickory-dock.zip :as dzip]
            [clojurewerkz.urly.core :as urly])
  (:import (java.net URI URL)))

(declare inline-html-file)
(declare inline-css-file)

(def excluded-html-imports (atom #{}))

(defn inline-js-scripts [hdat infile]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :script) (hsel/attr :src #(urly/relative? (java.net.URL. %)))) (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :src)]
        (util/info (str "Inlining Script... " nfile "\n"))
        (let [loc (zip/replace loc {:type :element :tag :script :contents (slurp (io/file (.getParent infile) nfile))})]
          (inline-js-scripts (zip/root loc)))))))

(defn inline-css-imports [cssdat infile]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/node-type :import) (dsel/url #(urly/relative? %))) (dzip/css-zip cssdat))]
    (if-not loc cssdat
      (let [nfile (-> loc zip/node :url)]
        (util/info (str "Importing Stylesheet... " nfile "\n"))
        (let [origcss (-> infile .getParent (io/file nfile))
              cssdat (-> origcss d/parse-stylesheet d/as-css (inline-css-imports origcss))
              loc (reduce #(zip/next (zip/insert-left %1 %2))
                          (zip/next (zip/remove loc))
                          (hsel/select (fn [loc] loc) cssdat))]
          (inline-css-imports (zip/root loc) infile))))))

(defn inline-html-css [hdat infile imports? polymer?]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :link) (hsel/attr :rel (partial = "stylesheet")) (hsel/attr :href urly/relative?)) (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)]
        (util/info (str "Inlining Stylesheet... " nfile "\n"))
        (let [cssdat (-> infile .getParent (io/file nfile) d/parse-stylesheet d/as-css)
              cssdat (if imports? (inline-css-imports cssdat (io/file (.getParent infile) nfile)) cssdat)
              loc (zip/replace loc (d/css-to-hickory cssdat (when polymer? {:is "custom-style"})))]
          (inline-html-css (zip/root loc) infile imports? polymer?))))))

(defn strip-html-excluded [hdat infile & [msg?]]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :link)
                                (hsel/attr :rel (partial = "import"))
                                (hsel/attr :href #(contains? @excluded-html-imports (.getCanonicalPath (io/file (.getParent infile) %)))))
                         (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)]
        (when-not msg? (util/dbug (str "Stripping HTML Imports... \n")))
        (util/dbug (str "• " nfile "\n"))
        (strip-html-excluded (zip/root (zip/remove loc)) infile true)))))

(defn inline-html-imports [hdat infile opts]
  (let [hdat (strip-html-excluded hdat infile)
        loc (hsel/select-next-loc
              (hsel/and (hsel/tag :link)
                        (hsel/attr :rel (partial = "import"))
                        (hsel/attr :href #(not (contains? @excluded-html-imports (.getCanonicalPath (io/file (.getParent infile) %))))))
              (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)
            npath (.getCanonicalPath (io/file (.getParent infile) nfile))]
        (swap! excluded-html-imports #(conj % npath))
        (util/info (str "Inlining HTML Imports: " nfile "\n"))
        (util/dbug (str "• " npath "\n"))
        (let [inline-file (inline-html-file (io/file (.getParent infile) nfile) @excluded-html-imports opts)
              loc (reduce #(zip/insert-left %1 %2)
                          (zip/next (zip/remove loc))
                          (hsel/select hsel/any inline-file))
                  ]
        (inline-html-imports (zip/root loc) infile opts))))))

(defn inline-html-file [infile excluded inline?]
  (let [hdat (-> infile slurp h/parse h/as-hickory)]
    (reset! excluded-html-imports excluded)
    (cond-> hdat
      (contains? inline? :scripts) (inline-js-scripts infile)
      (contains? inline? :css) (inline-html-css infile (contains? inline? :css-imports) (contains? inline? :polymer))
      (contains? inline? :html-imports) (inline-html-imports infile inline?))))

(defn select-html-scripts [hdat]
  (hsel/select (hsel/tag :script) hdat))

(defn select-scripts [infile]
  (apply str (mapcat :content (-> infile slurp h/parse h/as-hickory select-html-scripts))))

(defn strip-html-scripts [hdat]
  (let [loc (hsel/select-next-loc (hsel/tag :script) (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (-> loc zip/remove zip/root strip-html-scripts))))

(defn strip-scripts [infile]
  (-> infile slurp h/parse h/as-hickory strip-html-scripts))

(defn ref-js [hdat file body?]
  (let [loc (hsel/select-next-loc (if body? (hsel/tag :body) (hsel/tag :head)) (hzip/hickory-zip hdat))]
    (zip/root (zip/append-child loc {:type :element :tag :script :attrs {:src file :defer nil}}))))
