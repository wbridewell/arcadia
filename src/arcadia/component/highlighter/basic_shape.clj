(ns arcadia.component.highlighter.basic-shape
  "Evidence suggests that goals can affect where a person looks based on a
  small number of visual features, including shape. This component enables
  selecting the next target for covert visual attention based on a limited
  set of canonical shapes.

  Focus Responsive
  No.

  Default Behavior
  Attempts to associate a shape label with each image segment and produces a
  fixation request for each segment for which a label is available.

  Produces
   * a fixation for each segment that can be assigned a shape label, including:
       a :segment that contains the candidate segment for fixation,
       a :reason specifying that the request is due to segment shape, and
       a :shape argument that names the shape associated with the segment.

  Note: This is adapted from the shape-reporter component."
  (:import  java.util.ArrayList
            java.lang.Integer
            [org.opencv.core CvType Mat Size]
            org.opencv.imgproc.Imgproc
            org.opencv.imgcodecs.Imgcodecs
            org.opencv.utils.Converters
            [org.opencv.ml KNearest Ml])

  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [clojure.math.combinatorics :refer [cartesian-product]]
            [arcadia.utility.descriptors :as d]
            clojure.java.io))

(def training-file (.getPath (clojure.java.io/resource "neural-nets/shape-training-img.png")))
(def training-file-dimension [3 72])
(def training-example-dimension [20 20])
(def ^:parameter shape-labels ["triangle" "rectangle" "circle"])

(def training-examples
  (let [trainImg (Imgcodecs/imread training-file)
        trainImg32 (Mat.)
        trainImg32Grey (Mat.)
        ncol (first training-file-dimension)
        nrow (last training-file-dimension)
        xdim (first training-example-dimension)
        ydim (last training-example-dimension)
        xcoords (range 0 (* xdim ncol) xdim)
        ycoords (range 0 (* ydim nrow) ydim)
        coords (cartesian-product xcoords ycoords)
        training-mat (Mat.)]
    (.convertTo trainImg trainImg32 CvType/CV_32FC3)
    (Imgproc/cvtColor trainImg32 trainImg32Grey Imgproc/COLOR_BGR2GRAY)
    (doseq [x (map #(.submat trainImg32Grey (last %) (+ ydim (last %))
                             (first %) (+ xdim (first %))) coords)]
      (.push_back training-mat (.reshape (.clone x) 1 1)))
    training-mat))

;; assumes labels are by column
(def training-labels
  (vec (mapcat #(take (last training-file-dimension) (repeat (Integer. %))) (range (count shape-labels)))))

(def classifier
  (let [newclassifier (KNearest/create)
        examplesF (Mat.)
        examples training-examples
        labels (Converters/vector_int_to_Mat (ArrayList. training-labels))
        labels32f (Mat.)]
    (.convertTo labels labels32f CvType/CV_32F)
    (.convertTo examples examplesF CvType/CV_32F)
    (.train newclassifier  examplesF Ml/ROW_SAMPLE labels32f)
    newclassifier))

(defn- format-segment [segment]
  (let [sized (Mat.)
        greyimg (Mat.)
        threshimg (Mat.)
        xdim (first training-example-dimension)
        ydim (last training-example-dimension)]
    (Imgproc/resize (:mask segment) sized (Size. xdim ydim))
    (.convertTo sized sized CvType/CV_32F)
    (.reshape (.clone sized) 1 1)))

(defn- aspect-sensitive-label [rawlabel ratio]
  (cond
    (and (= rawlabel "rectangle") (>= ratio 0.95))
    "square"

    (and (= rawlabel "circle") (< ratio 0.95))
    "oval"

    :else
    rawlabel))

(defn- shape-label [segment params]
  (let [pseg (format-segment segment)
        results (Mat.)
        width (-> segment :region :width)
        height (-> segment :region :height)
        ratio (/ (min width height) (max width height))
        label (aspect-sensitive-label (nth shape-labels (int (.findNearest classifier pseg 3 results))) ratio)]
    label))


(defn- make-fixation [segment params component]
  (when-let [label (shape-label segment params)]
    {:name "fixation"
     :arguments {:segment segment :reason "shape" :shape label}
     :world nil
     :source component
     :type "instance"}))

(defrecord BasicShapeHighlighter [parameters buffer]
  Component
  (receive-focus
    [component focus content]
    (->> (d/first-element content :name "image-segmentation")
         :arguments :segments
         (map #(make-fixation % parameters component))
         (filter some?)
         (reset! (:buffer component))))

  (deliver-result
    [component]
    (set @(:buffer component))))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->BasicShapeHighlighter p (atom nil))))
