(ns degree9.boot-polymer.impl
  (:require [clojure.java.io :as io]
            [boot.util :as util]
            [hickory.core :as h]
            [dickory-dock.core :as d]
            [hickory.select :as hsel]
            [dickory-dock.select :as dsel]
            [clojure.zip :as zip]
            [hickory.zip :as hzip]
            [dickory-dock.zip :as dzip]))

(declare inline-html-file)
(declare inline-css-file)

(def excluded-html-imports (atom #{}))

(def url-regex #"^((http[s]?|ftp):\/)?\/?([^:\/\s]+)((\/\w+)*\/)([\w\-\.]+[^#?\s]+)(.*)?(#[\w\-]+)?$")

(defn inline-js-scripts [hdat infile]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :script) (hsel/attr :src #(not (re-matches url-regex %)))) (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :src)]
        (util/info (str "Inlining Script... " nfile "\n"))
        (let [loc (zip/replace loc {:type :element :tag :script :contents (slurp (io/file (.getParent infile) nfile))})]
          (inline-js-scripts (zip/root loc)))))))

(defn inline-css-imports [cssdat infile]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/node-type :import) (dsel/url #(not (re-matches url-regex %)))) (dzip/css-zip cssdat))]
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
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :link) (hsel/attr :rel (partial = "stylesheet"))) (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)]
        (util/info (str "Inlining Stylesheet... " nfile "\n"))
        (let [cssdat (-> infile .getParent (io/file nfile) d/parse-stylesheet d/as-css)
              cssdat (if imports? (inline-css-imports cssdat (io/file (.getParent infile) nfile)) cssdat)
              loc (zip/replace loc (d/css-to-hickory cssdat (when polymer? {:is "custom-style"})))]
          (inline-html-css (zip/root loc) infile imports? polymer?))))))

(defn strip-html-excluded [hdat & [msg?]]
  (let [loc (hsel/select-next-loc (hsel/and (hsel/tag :link)
                                (hsel/attr :rel (partial = "import"))
                                (hsel/attr :href (partial contains? @excluded-html-imports)))
                         (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)]
        (when-not msg? (util/info (str "Stripping HTML Imports... \n")))
        (util/info (str "• " nfile "\n"))
        (strip-html-excluded (zip/root (zip/remove loc)) true)))))

(defn inline-html-imports [hdat infile opts]
  (let [hdat (strip-html-excluded hdat)
        loc (hsel/select-next-loc
              (hsel/and (hsel/tag :link)
                        (hsel/attr :rel (partial = "import"))
                        (hsel/attr :href #(not (contains? @excluded-html-imports %))))
              (hzip/hickory-zip hdat))]
    (if-not loc hdat
      (let [nfile (-> loc zip/node :attrs :href)]
        (swap! excluded-html-imports #(conj % nfile))
        (util/info (str "Inlining HTML Imports: " nfile "\n"))
        (let [inline-file (inline-html-file (io/file (.getParent infile) nfile) @excluded-html-imports opts)
              loc (reduce #(zip/next (zip/insert-left %1 %2))
                          (zip/next (zip/remove loc))
                          (hsel/select
                            (hsel/or (hsel/child (hsel/tag :head) hsel/any)
                                     (hsel/child (hsel/tag :body) hsel/any))
                            inline-file))]
        (inline-html-imports (zip/root loc) infile opts))))))

(defn inline-html-file [infile excluded inline?]
  (let [hdat (-> infile slurp h/parse h/as-hickory)]
    (reset! excluded-html-imports excluded)
    (cond-> hdat
      (contains? inline? :scripts) (inline-js-scripts infile)
      (contains? inline? :css) (inline-html-css infile (contains? inline? :css-imports) (contains? inline? :polymer))
      (contains? inline? :html-imports) (inline-html-imports infile inline?))))
