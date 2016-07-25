(ns degree9.boot-polymer
  {:boot/export-tasks true}
  (:require [clojure.java.io :as io]
            [clojure.zip :as zip]
            [boot.core :as boot]
            [boot.task.built-in :as tasks]
            [boot.tmpdir :as tmpd]
            [boot.util :as util]
            [degree9.boot-polymer.impl :as impl]
            [silicone.core :as silicone]
            [hickory.render :as rend]
            [hickory.select :as hsel]
            [hickory.zip :as hzip]))

(boot/deftask crisper
  "boot-clj wrapper for crisper"
  [i input         VAL     str      "Input file to split."
   o html          VAL     str      "HTML output will be written to this file."
   j javascript    VAL     str      "JS output will be written to this file."
   b body                  bool     "In HTML output include script in body."]
  (let [input    (:input         *opts* "index.html")
        html-out (:html          *opts* input)
        js-out   (:javascript    *opts* (.replaceAll html-out "\\.html$" ".js"))
        in-body  (:body   *opts*)
        tmp      (boot/tmp-dir!)
        tmp-path (.getAbsolutePath tmp)]
    (boot/with-pre-wrap fileset
      (util/dbug (str "Crisper Tmp Path:" tmp-path "\n"))
      (util/info (str "Splitting file... \n"))
      (let [files (->> fileset
                    (boot/output-files)
                    (boot/by-name [input])
                    (map (juxt boot/tmp-path boot/tmp-file)))]
          (doseq [[path file] files]
            (io/make-parents (io/file tmp path))
            (io/copy file (io/file tmp path)))
          (let [file          (io/file tmp input)
                html-contents (impl/strip-scripts file)
                html-contents (impl/ref-js html-contents js-out in-body)
                js-contents   (impl/select-scripts file)]
            (util/info (str "• " input " -> " html-out " & " js-out "\n"))
            (doto (io/file tmp html-out) io/make-parents (spit (rend/hickory-to-html html-contents)))
            (doto (io/file tmp js-out) io/make-parents (spit js-contents)))
          (-> fileset (boot/add-resource tmp) boot/commit!)))))

(boot/deftask vulcanize
  "Inline HTML Imports, Scripts and CSS."
  [i input         VAL     str      "Input HTML file to vulcanize."
   o output        VAL     str      "Output will be written to this file."
   m html                  bool     "Inline HTML Imports."
   e excluded      VAL     #{str}   "Strip (exclude) HTML Imports."
   d document              bool     "Optionally wrap output in: <html><head></head></html>"
   c css                   bool     "Inline css files. (Resolved relative to original html file)"
   f follow                bool     "Follow css @import. (Does not follow url's, Resolves relative to original css file)"
   p polymer               bool     "Include :is \"custom-style\" attribute in <style> tag"
   s scripts               bool     "Inline JavaScript files. (Resolves relative to input file)"]
  (let [input    (:input  *opts* "index.html")
        output   (:output *opts* input)
        tmp      (boot/tmp-dir!)
        tmp-path (.getAbsolutePath tmp)]
    (boot/with-pre-wrap fileset
      (util/dbug (str "Vulcanize Tmp Path:" tmp-path "\n"))
      (util/info (str "Vulcanizing file... \n"))
      (let [files (->> fileset
                    (boot/output-files)
                    (map (juxt boot/tmp-path boot/tmp-file)))]
        (doseq [[path file] files]
          (io/make-parents (io/file tmp path))
          (io/copy file (io/file tmp path)))
        (let [opts (cond-> #{}
                     (:html *opts*)    (conj :html-imports)
                     (:css *opts*)     (conj :css)
                     (:follow *opts*)  (conj :css-imports)
                     (:polymer *opts*) (conj :polymer)
                     (:scripts *opts*) (conj :scripts))
              html-out (impl/inline-html-file (io/file tmp input) (:excluded *opts* #{}) opts)
              html-zip (hzip/hickory-zip html-out)]
          (util/info (str "• " input " -> " output "\n"))
          (doto (io/file tmp output)
            io/make-parents
            (spit
              (rend/hickory-to-html
                (if (:document *opts*) html-out
                  (zip/root
                    (zip/edit html-zip
                      #(assoc-in % [:content]
                        (hsel/select (hsel/child (hsel/tag :head) hsel/any) html-out)))))))))
        (-> fileset (boot/add-resource tmp) boot/commit!)))))

(boot/deftask polymer
  "Generates a polymer elements file"
  [d directory  VAL str "Directory to import elements from."
   e elements   VAL str "Polymer elements to import."
   o html       VAL str "HTML output will be written to this file."
   j javascript VAL str "JS output will be written to this file."]
  (let [elements (:elements   *opts* #{:polymer :iron-elements :paper-elements :neon-animation})
        html-out (:html       *opts* "elements.html")
        js-out   (:javascript *opts* "elements.js")
        dir      (:directory  *opts* "bower_components")
        tmp      (boot/tmp-dir!)
        tmp-path (.getAbsolutePath tmp)]
    (comp
      (boot/with-pre-wrap fileset
        (let [html (silicone/polymer-elements elements dir)]
          (util/info "Writing polymer elements file...\n")
          (doto (io/file tmp html-out)
            (io/make-parents)
            (spit html))
          (-> fileset (boot/add-resource tmp) boot/commit!)))
      (vulcanize :input html-out :output "vulcanize.html")
      (crisper :input "vulcanize.html" :html html-out :javascript js-out))))
