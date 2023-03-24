(ns arcadia.component.ocr-segmenter
  (:import (net.sourceforge.tess4j Tesseract ITessAPI$TessPageIteratorLevel))
  (:require [clojure.string :as s]
            [clojure.java.io]
            [arcadia.utility
             [image :as img]
             [opencv :as cv]]
            [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :refer [poll]]
            [arcadia.utility.geometry :as geo]))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)
(def ^:parameter segment-type "Segment by :word, :line, :sentence, or :block. Default is :block."
  :block)

(defn setup-ocr []
  (let [ocr (Tesseract.)]
    (.setDatapath ocr (.getPath (clojure.java.io/resource "tesseract/tessdata")))
    ;  (.setTessVariable ocr "tessedit_pageseg_mode" "7") user_defined_dpi
    (.setTessVariable ocr "preserve_interword_spaces" "1")
    (.setTessVariable ocr "user_defined_dpi" "300")
    (.setTessVariable ocr "debug_file" "/dev/null")
    ocr))

(defn- ocr_word->segment
  [word image]
  (let [bounds (.getBoundingBox word)
        region {:x (.x bounds) :y (.y bounds) :width (.width bounds)
                :height  (.height bounds)}
        mask (-> image (cv/submat region) (cv/cvt-color cv/COLOR_BGR2HSV)
                 (cv/in-range [0 50 0] [255 255 255]))]
    {:image (cv/copy (cv/submat image region) :mask mask)
     :mask mask
     :area (cv/count-non-zero mask)
     :region region
     :text (-> (.getText word) s/trim (s/replace "\n" " "))}))

(defn- merge-segments
  [seg1 seg2]
  {:region (geo/union (:region seg1) (:region seg2))
   :text (str (:text seg1) " " (:text seg2))})

(defn partition-after
  "Partitions after each item in coll for which f is true. Input should be
   [f coll]"
  ([f coll so-far]
   (cond
     (empty? coll)
     (list (reverse so-far))
     (-> coll first f not)
     (partition-after f (rest coll) (cons (first coll) so-far))
     :else
     (cons (reverse (cons (first coll) so-far)) (partition-after f (rest coll) nil))))
  ([f coll]
   (remove empty? (partition-after f coll nil))))

(defn- find-sentences
  [words]
  (map #(reduce merge-segments %)
       (partition-after #(#{\. \! \?} (last (:text %))) words)))

(defn do-ocr [ocr cv_img params]
  (let [img (img/mat-to-bufferedimage cv_img)]
    (condp = (:segment-type params)
      :word (map #(ocr_word->segment % cv_img)
                 (.getWords ocr img (. ITessAPI$TessPageIteratorLevel RIL_WORD)))
      :block (map #(ocr_word->segment % cv_img)
                  (.getWords ocr img (. ITessAPI$TessPageIteratorLevel RIL_BLOCK)))
      :line (map #(ocr_word->segment % cv_img)
                 (.getWords ocr img (. ITessAPI$TessPageIteratorLevel RIL_TEXTLINE)))
      :sentence (find-sentences
                 (map #(ocr_word->segment % cv_img)
                      (.getWords ocr img (. ITessAPI$TessPageIteratorLevel RIL_WORD))))
      (throw (Exception. "Unexpected segment-type for OCR.")))))

(defn- get-segments
  "Make blame-concept interlingua element for intentionality information
   about the norm violation."
  [img ocr content component params]
  (let [segments (do-ocr ocr img params)]
    {:id (gensym)
     :name "text-segmentation"
     :arguments {:segments segments
                 :image img
                 :sensor (:sensor component)}
     :type "instance"
     :world nil}))

(defrecord OCRSegmenter [sensor ocr buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (when-let [img (-> (poll (:sensor component)) :image)]
      (reset! (:buffer component) (get-segments img ocr content component parameters))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method OCRSegmenter [comp ^java.io.Writer w]
  (.write w (format "OCRSegmenter{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->OCRSegmenter (:sensor p) (setup-ocr) (atom nil) p)))
