(ns arcadia.component.semantics.word
  (:import (net.sourceforge.tess4j Tesseract ITessAPI$TessPageIteratorLevel))
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.utility.image :as img]
            [clojure.java.io]
            [clojure.string :as s]))

;;
;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;
;;

(def ^:parameter base-strength "the strength value of this component without any enhancement"
  0.9)

(defn setup-ocr []
  (doto (Tesseract.)
    (.setDatapath (.getPath (clojure.java.io/resource "tesseract/tessdata")))
    (.setTessVariable "preserve_interword_spaces" "1")
    (.setTessVariable "user_defined_dpi" "300")
    (.setTessVariable "debug_file" "/dev/null")))



(defn- do-ocr [ocr cv_img]
    ;; add a border to the image to improve ocr
  (let [img (-> cv_img (cv/copy-make-border 50 50 50 50 cv/BORDER_CONSTANT :value 0)
                img/mat-to-bufferedimage)]
    ;      (img/mat-to-bufferedimage cv_img)]
    ;; XXX: assume there is only one word
    (first
     (map #(-> (.getText %) s/trim (s/replace "\n" " "))
          (.getWords ocr img (. ITessAPI$TessPageIteratorLevel RIL_WORD))))))

;; Outputs basic word category information for stroop. Not sophisticated.
(defn- word-category [str]
  (if (#{"red" "green" "blue"} str)
    :color
    :object))

(defrecord WordSemantics [buffer parameters enhancement ocr]
  Component
  (receive-focus
   [component focus content]
   ;; start over with a new task
   (when (d/element-matches? focus :name "switch-task")
     (reset! (:enhancement component) 0))
   (when-let [new-parameters (:arguments (d/first-element content
                                                          :name "update-word-semantics"
                                                          :type "automation" :world nil))]
     (when (contains? new-parameters :enhancement)
       (reset! (:enhancement component) (-> new-parameters :enhancement))))

   (reset! (:buffer component) nil)
   (when-let [img (cond (d/element-matches? focus :name "object" :world nil) (-> focus :arguments :mask)
                        (d/element-matches? focus :name "fixation" :world nil) (-> focus :arguments :segment :image))]
     (reset! (:buffer component)
             (let [word (do-ocr (:ocr component) img)
                   property (word-category word)]
               (when word
                 {:name "semantics"
                  :arguments {:property property
                              property word
                              :strength  (+ (-> component :parameters :base-strength)
                                            @(:enhancement component))
                              :path :word}
                  :type "instance"
                  :world nil})))))
  
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method WordSemantics [comp ^java.io.Writer w]
  (.write w (format "WordSemantics{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->WordSemantics (atom nil) p (atom 0) (setup-ocr))))