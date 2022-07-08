(ns arcadia.component.image-segmenter
  "This component carves the visual field into proto-object regions. That is,
  it identifies which areas are likely to contain objects and attempts to put
  boundaries around those objects.

  The functionality of this component is limited, as it implements a crude
  approach to image segmentation that is useful for laboratory stimuli, but
  not useful for complex imagery. The behavior of this component can be
  adapted to task-specific stimuli as long as it outputs the same sort of
  bounding boxes and shape outlines for the proto-objects.

  Focus Responsive
  No.

  Default Behavior
  Read an image from the associated sensor and return the regions of the
  image that are likely to contain objects of interest.

  Produces
   * image-segmentation
       includes a list of :segments extracted from the visual field, each of
       which has a corresponding
         * image matrix (:image),
         * mask matrix (:mask),
         * :area of the segment,
         * a rectangular bounding :region, and
         * nested :subsegments of the segment.
       the image-segmentation also includes the original
         * :image that was processed,
         * the :sensor that delivered the image,
         * :gaze information when the segmentation was carried out, and,
         * :true-segments, which includes the segmentation results even if
           segmentation is officially \"turned off\" during saccades."
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.vision.segments :as seg]
            [arcadia.sensor.core :as sensor]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(def ^:parameter remove-white? "remove whitespace before segmenting?" false)
(def ^:parameter min-saturation "Convert all pixels whose color saturation is below
this level (scale of 0-1) to black before finding segments." nil)
(def ^:parameter inner-contours? "compute the contours inside each external contour?" false)
(def ^:parameter all-contours? "Treat all contours (inner and external) equally?
This supercedes inner-contours?" false)
(def ^:parameter use-colors? "use color information to segment the image?" false)
(def ^:parameter smooth-image? "perform smoothing before finding contours?" true)
(def ^:parameter use-custom-BGR-mapping? "use a custom mapping from BGR to grayscale to segment the image?" false)
(def ^:parameter custom-BGR-mapping "custom mapping from BGR to grayscale" [0.3 0.3 0.3])
(def ^:parameter use-color-channel? "use a particular channel from the R/G B/Y opponents?" false)
(def ^:parameter channel "should be nil, or one of :red, :green, :blue, and :yellow" nil)

;; these parameters only affect behavior if :inner-contours? is true
(def ^:parameter min-segment-area "min area for a segment" 500.0)
(def ^:parameter max-segment-area "max area for a segment" 25000.0)
(def ^:parameter min-segment-length "minimum length (width/height) for a segment" 5.0)
(def ^:parameter max-segment-length "maximum length (width/height) for a segment" 2500.0)

;; If a contour's inner contours are above this percentage of the
;; contour's area, discard the contour from the contour hierarchy
(def ^:parameter max-inner-contour-ratio
   "If a contour's inner contours are above this percentage of the
   contour's area, discard the contour from the contour hierarchy."
  0.75)

(defn- remove-white
  "Convert white pixels to black ones."
  [image-mat]
  (let [mask (cv/bitwise-not! (cv/in-range image-mat [240 240 240] [255 255 255]))]
    (-> image-mat (cv/copy :mask mask) (cv/morphology-ex! cv/MORPH_OPEN [4 4]))))

(defn- apply-min-saturation
  "Change everything below the min saturation to black before segmenting."
  [image thresh]
  (let [mask (-> image (cv/cvt-color cv/COLOR_BGR2HSV)
                 (cv/in-range! [0 (int (* thresh 255)) 0] [255 255 255]))]
    (cv/copy image :mask mask)))

(defn- get-input
  "Converts the original image into the appropriate input for finding edges, according
  to our parameters."
  [image-mat params]
  (cond
    (:use-custom-BGR-mapping? params)
    (cv/transform-bgr image-mat (:custom-BGR-mapping params))

    (and (:use-color-channel? params) (= (:channel params) :red))
    (-> (cv/transform-bgr image-mat [0 -1 1]) (cv/max! 0))

    (and (:use-color-channel? params) (= (:channel params) :green))
    (-> (cv/transform-bgr image-mat [0 1 -1]) (cv/max! 0))

    (and (:use-color-channel? params) (= (:channel params) :blue))
    (let [[B G R] (cv/split image-mat)]
      (-> (cv/subtract! B (cv/min G R)) (cv/max! 0)))

    (and (:use-color-channel? params) (= (:channel params) :yellow))
    (let [[B G R] (cv/split image-mat)]
      (-> (cv/subtract! (cv/min G R) B) (cv/max! 0)))

    (not (:use-colors? params))
    (cv/cvt-color image-mat cv/COLOR_BGR2GRAY)

    :else
    image-mat))

;;--------------------------Contour Hierarchy Processing------------------------
(defn- same-segment-area?
  "True when a small segment has approximately the same area as the
  larger segment."
  [outer-segment inner-segment params]
  ;; presumably the small contour is inside the big contour and this
  ;; checks to see whether it makes sense to ignore one of them.
  (> (/ (:area inner-segment) (:area outer-segment))
     (:max-inner-contour-ratio params)))

(defn- get-subsegments
  "Returns all subsegments of a segment, skipping over those subsegments that
   are about the same size as the outer segment."
  [segment params]
  (mapcat #(if (same-segment-area? segment % params)
             (get-subsegments % params)
             (list %))
          (:subsegments segment)))

(defn- correct-size?
  "Determine if a segment is properly sized according to the
  :min-segment-area, :max-segment-area, :min-segment-length,
  and :max-segment-length parameter values."
  [segment {:keys [min-segment-area max-segment-area min-segment-length
                   max-segment-length]}]
  (let [a (:area segment)]
    (and (>= a min-segment-area)
         (<= a max-segment-area)
         (-> segment :region :width (>= min-segment-length))
         (-> segment :region :width (<= max-segment-length))
         (-> segment :region :height (>= min-segment-length))
         (-> segment :region :height (<= max-segment-length)))))

(defn- get-smallest-subsegments
  "Given a contour list, extract the smallest subsegments
   larger than min-segment-area"
  [segment params]
  (loop [candidates (list segment)
         segments nil]
    (let [candidate (first candidates)
          subsegments (and candidate (get-subsegments candidate params))]
      (cond
        ;; all of the segment candidates have been considered
        (empty? candidates) segments

        ;; the first candidate's subsegments are large enough,
        ;; so consider those instead of the candidate
        (some #(correct-size? % params) subsegments)
        (recur (concat subsegments (rest candidates))
               segments)

        ;; the candidate is large enough to be a segment
        (correct-size? candidate params)
        (recur (rest candidates)
               (conj segments (assoc candidate :subsegments subsegments)))
        ;; the candidate and subsegments are too small, so forget about it
        :else
        (recur (rest candidates) segments)))))

(defn- setup-segment
  "Converts a segment returned by the opencv library to the appropriate format for
   arcadia's use."
  [segment image]
  (assoc (seg/prepare segment :input image)
         :subsegments (map #(setup-segment % image) (:subsegments segment))))

(defn- get-segments
  "Given an OpenCV image, return a list of segments, hash-maps of the form
   {:region region :mask mask :image :image} describing regions of interest in
   the image."
  [image-mat params]
  (-> image-mat
      (cond-> (:remove-white? params) remove-white
              (:min-saturation params) (apply-min-saturation (:min-saturation params)))
      (get-input params) (cv/canny 50 255)
      (cond->
       (:smooth-image? params) (cv/gaussian-blur! [3 3])
       (:all-contours? params) (cv/find-segments-by-contour cv/RETR_LIST)
       (:inner-contours? params) (-> (cv/find-segments-by-contour cv/RETR_TREE)
                                     (->> (mapcat #(get-smallest-subsegments % params))))
       (not (or (:all-contours? params)
                (:inner-contours? params)))
       (cv/find-segments-by-contour cv/RETR_EXTERNAL))
      (->> (map #(setup-segment % image-mat)))))

(defrecord ImageSegmenter [sensor buffer gaze-buffer data-buffer parameters]
  Component
  (receive-focus
   [component focus content]
   (let [data (sensor/poll (:sensor component))]
     (reset! (:gaze-buffer component) (d/first-element content :name "gaze"))
     (reset! (:data-buffer component) (:image data))
     (when (:image data)
       (reset! (:buffer component)
               (get-segments (:image data) (:parameters component))))))

  (deliver-result
   [component]
   #{{:name "image-segmentation"
      :arguments {:segments (when (not (:saccading? (:arguments @(:gaze-buffer component))))
                              @(:buffer component))
                  :true-segments @(:buffer component)
                  :image @(:data-buffer component)
                  :sensor (:sensor component)
                  :gaze @(:gaze-buffer component)}
      :world nil
      :source component
      :type "instance"}}))

(defmethod print-method ImageSegmenter [comp ^java.io.Writer w]
  (.write w (format "ImageSegmenter{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->ImageSegmenter (:sensor p) (atom nil) (atom nil) (atom nil) p)))
