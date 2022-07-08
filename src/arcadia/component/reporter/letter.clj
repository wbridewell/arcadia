(ns arcadia.component.reporter.letter
  "People recognize characters surprisingly quickly. This component uses an
  OCR approach to mimic that capability.

  Use of OCR means that the component inherits all the problems associated
  with generalized OCR. The character has to be black on white, large enough
  to be discernable, and rectified. This means that some amount of image
  processing may be necessary.

  Focus Responsive
    * object

  Reports the character that appears on the object if there is one. Limited to
  single character detection.

  Default Behavior
  None.

  Produces
   * object-property
       includes a :property argument that specifies :character, the string
       :value of the character, and the :object."
  (:import net.sourceforge.tess4j.Tesseract)
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [image :as img] [objects :as obj] [opencv :as cv]]
            [clojure.java.io :refer [resource]]
            [clojure.string :refer [trim]]))

(def ^:parameter whitelist "a list of characters to prefer in recognition" nil)
(def ^:parameter remove-border?
  "if the letter is inside a closed contour, we need to remove the outermost contour to get a clean image."
  nil)

;; These transformations appear to work well with the ball examples
;; and using Tesseract for OCR. Generality of applicability is not assured.
(defn gank
  "Transform an image to the point that Tesseract OCR identify any letters."
  [img mask]
  (-> img (cv/cvt-color cv/COLOR_BGR2GRAY) (cv/convert-to cv/CV_8UC1)
      (cv/gaussian-blur! [3 3])
      (cv/threshold! 10 cv/THRESH_BINARY :max-value 255)
      (cv/erode! [2 2])
      (cv/copy :dst (cv/new-mat img :type cv/CV_8UC1 :value 255) :mask mask)
      (cv/resize [300 300])
      ;(cv/gaussian-blur! [3 3]) <--Due to an error in the old code, this step wasn't happening
      img/mat-to-bufferedimage))

(defn tesseract-ocr
  "Use OCR to identify a single letter in an image."
  [img whitelist]
  (let [ocr (Tesseract.)]
    ;; if you know there will be a limited set of characters, you can enhance
    ;; precision by whitelisting them.
    (when whitelist
      (.setTessVariable ocr "tessedit_char_whitelist" (str whitelist)))
    ;; ask Tesseract to assume that there is only one character in the image.
    (.setDatapath ocr (.getPath (resource "tesseract/tessdata")))
    (.setTessVariable ocr "tessedit_pageseg_mode" "10")
    (let [output (trim (.doOCR ocr img))]
      output)))

(defrecord LetterReporter [buffer object params]
  Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component)
           (when (and (= (:type focus) "instance")
                      (= (:name focus) "object")
                      (= (:world focus) nil))
             (reset! (:object component) focus)
             ;; OCR is fiddly, so it's useful to see what's going on in this component.
             ;; Below is some reasonable debugging/inspection code.
             ;;  (println "LetterReporter sees: " @(:buffer component)))
             ;;
             ;; (display-image! frame (mat-to-bufferedimage (:image (:arguments focus)))))
             ;;  (display-image! frame (mat-to-bufferedimage (:mask (:segment (:arguments focus))))))
             ;; (display-image! frame (gank (:image (:arguments focus)) (:mask (:arguments focus))))
             (let [segment (obj/get-segment focus content)]
               (if (and (-> component :params :remove-border?)
                        (seq (:subsegments segment)))
                 (apply str (map #(tesseract-ocr (gank (:image %) (:mask %))
                                                 (:whitelist (:params component)))
                                 (:subsegments segment)))
                 (tesseract-ocr (gank (-> focus :arguments :image)
                                      (-> focus :arguments :mask))
                                (:whitelist (:params component))))))))
  (deliver-result
   [component]
   (when (and @(:buffer component) (not (.isEmpty @(:buffer component))))
     ;(println "   LETTER REPORTER-------->" @(:buffer component))
     #{{:name "object-property"
        ;; might be useful to also return the segment
        :arguments {:property :character
                    :value @(:buffer component)
                    :object @(:object component)}
        :world nil
        :source component
        :type "instance"}})))

(defmethod print-method LetterReporter [comp ^java.io.Writer w]
  (.write w (format "LetterReporter{}")))

(defn start [& {:as args}]
  (->LetterReporter (atom nil) (atom nil) (merge-parameters args)))
