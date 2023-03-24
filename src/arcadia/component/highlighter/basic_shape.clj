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
  (:import  [org.opencv.ml KNearest Ml])

  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [clojure.math.combinatorics :refer [cartesian-product]]
            [arcadia.utility.descriptors :as d]
            clojure.java.io
            [arcadia.utility.opencv :as cv]))

(def ^:private training-file (.getPath (clojure.java.io/resource "neural-nets/shape-training-img.png")))
(def ^:private training-file-dimension [3 72])
(def ^:private training-example-dimension [20 20])
(def ^:parameter shape-labels ["triangle" "rectangle" "circle"])

(def training-examples
  (let [trainImg (-> (cv/im-read training-file) (cv/convert-to cv/CV_32FC3) (cv/cvt-color cv/COLOR_BGR2GRAY))
        [ncol nrow] training-file-dimension
        [xdim ydim] training-example-dimension
        xcoords (range 0 (* xdim ncol) xdim)
        ycoords (range 0 (* ydim nrow) ydim)
        coords (cartesian-product xcoords ycoords)]
    (apply cv/vconcat
           (map (fn [[x y]] (-> (cv/submat trainImg x y xdim ydim) cv/copy (cv/reshape {:height 1})))
                coords))))

;; assumes labels are by column
(def training-labels
  (let [[_ n] training-file-dimension]
    (float-array (mapcat #(take n (repeat %)) (range (count shape-labels))))))

(def classifier
  (let [newclassifier (KNearest/create)
        labels (cv/transpose (cv/->java training-labels))]
    (.train newclassifier training-examples Ml/ROW_SAMPLE labels)
    newclassifier))

(defn- format-segment [segment]
  (let [[xdim ydim] training-example-dimension]
    (-> segment :mask (cv/resize {:width xdim :height ydim})
        (cv/convert-to cv/CV_32F) (cv/reshape {:height 1}))))

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
        width (-> segment :region :width)
        height (-> segment :region :height)
        ratio (/ (min width height) (max width height))
        label (aspect-sensitive-label (nth shape-labels (int (.findNearest classifier pseg 3 (cv/new-java-mat)))) ratio)]
    label))

(defn- make-fixation [segment params]
  (when-let [label (shape-label segment params)]
    {:name "fixation"
     :arguments {:segment segment :reason "shape" :shape label}
     :world nil
     :type "instance"}))

(defrecord BasicShapeHighlighter [buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (->> (d/first-element content :name "image-segmentation")
         :arguments :segments
         (map #(make-fixation % parameters))
         (filter some?)
         (reset! (:buffer component))))

  (deliver-result
    [component]
    @buffer))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->BasicShapeHighlighter (atom nil) p)))
