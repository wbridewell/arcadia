(ns
  ^{:doc "Functions for building object location maps."}
  arcadia.utility.object-location-map
  (:require (arcadia.utility [vectors :as vec]
                             [general :as g]
                             [opencv :as cv])
            [arcadia.vision.regions :as reg]
            [clojure.math.numeric-tower :as math]
            clojure.java.io))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Functions

(defn- get-mexhat-divisor
  "Determines the best divisor for downscaling a Mexican hat, given the enhanced region radius."
  [w params]
  (let [divisors (reverse (rest (range (:max-divisor params))))]
    (or (g/seek #(> (/ w %) (:min-w params)) divisors)
        1.0)))

(defn- make-odd
  "Makes an integer odd, adding 1 if necessary."
  [i]
  (if (odd? i)
    i
    (+ 1 i)))

(defn- dist-from-origin
  "Computes the distance from x y values to [0 0]."
  [x y]
  (math/sqrt (+ (* x x) (* y y))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for generating Mexican hat images

(defn mex-hat
  "Computes a 1D Mexican hat wavelet."
  [x s]
  (* (/ 2
        (* (math/sqrt (* 3 s)) (Math/pow Math/PI 0.25)))
     (- 1
        (/ (Math/pow x 2) (Math/pow s 2)))
     (Math/exp (* -1 (/ (Math/pow x 2)
                        (* 2 (Math/pow s 2)))))))

;;;;
;; Make a Mexican hat image that can be stamped at different locatios on the
;; image to make an object location map.
;; Uses the 1d mex-hat function, but computes values throughout a 2d image
;; based on distances from the origin.
;; radius gives us the size of the image
;; k is a multiplier for the height
;; w is a multiplier for the width
;; l is an addition to the elevation
;; The above parameters are specifically for the positive porition of the hat.
;; The negative portion is determined by k-neg w-neg and l-neg.
;;
;; Note that the second function below takes four additional parameters: k2, l2, k-neg2, l-neg2.
;; That function makes two Mexican hats in parallel. The hats are
;; the same size, but the amplitudes are scaled separately.

(defn make-mex-hat-image
  "Returns a 2D Mexican hat image as an OpenCV matrix."
  ([radius k w l k-neg w-neg l-neg]
;;    (println :image "Making mex hat..." (+ (* radius 2) 1))
   (let [width (+ (* radius 2) 1)
         img (cv/new-java-mat [width width] cv/CV_32FC1)]
     (loop [row-idx 0
            buffer (vector-of :double)]
       (if (< row-idx width)
         (recur
           (inc row-idx)
           ;; add row values to buffer
           (loop [col-idx 0
                  row buffer]
             (let [i (math/sqrt (+ (Math/pow (- col-idx radius) 2)
                                   (Math/pow (- row-idx radius) 2)))
                   value (mex-hat (if (< i w)
                                    (/ i w)
                                    (+ 1 (/ (- i w) w-neg)))
                                  1)]
               (if (< col-idx width)
                 (recur (inc col-idx)
                        ;; add value to row
                        (if (> value 0)
                          (conj row (+ l (* value k)))
                          (conj row (+ l-neg (* value k-neg)))))
                 row))))
         ;; push the buffer into the matrix
         (cv/set-values img 0 0 (double-array buffer))))
     img)))

(defn- make-mex-hat-image-set
  "Returns two 2D Mexican hat images as OpenCV matrixes. The second hat consists of
  only the negative portion and has is scaled separately, according to k-neg2 and l-neg2.
  Actually returns three values: 1) the positive component of the first hat, 2) the negative
  component of the first hat, 3) the negative component of the second hat."
  [radius k w l k-neg w-neg l-neg k-neg2 l-neg2 params]
  (let [div (get-mexhat-divisor w params)
        radius (int (/ radius div))
        w (/ w div)
        w-neg (/ w-neg div)
        width (+ (* radius 2) 1)
        pos-width (+ (* (int w) 2) 1)
        pos-min (int (/ (- width pos-width) 2))
        pos-max (+ pos-min (- pos-width 1))
        img (cv/new-java-mat [pos-width pos-width] cv/CV_32FC1)
        img2 (cv/new-java-mat [width width] cv/CV_32FC1)
        img3 (cv/new-java-mat [width width] cv/CV_32FC1)]
    (loop [row-idx 0
           ;;Uses two buffers for the two Mexican hat images
           [buffer buffer2 buffer3] [(vector-of :double) (vector-of :double)
                                     (vector-of :double)]]
      (if (< row-idx width)
        (recur
          (inc row-idx)
          ;; add row values to buffers
          (loop [col-idx 0
                 row buffer
                 row2 buffer2
                 row3 buffer3]
            (let [i (math/sqrt (+ (Math/pow (- col-idx radius) 2)
                                  (Math/pow (- row-idx radius) 2)))
                  value (mex-hat (if (< i w)
                                   (/ i w)
                                   (+ 1 (/ (- i w) w-neg)))
                                 1)]
              (if (< col-idx width)
                (recur (inc col-idx)
                       ;; add value to row
                       (cond
                         (not (and (>= col-idx pos-min) (>= row-idx pos-min)
                                   (<= col-idx pos-max) (<= row-idx pos-max)))
                         row
                         (> value 0)
                         (conj row (+ l (* value k)))
                         :else
                         (conj row 0))

                       (if (> value 0)
                         (conj row2 0)
                         (conj row2 (+ l-neg (* value k-neg))))

                       (if (> value 0)
                         (conj row3 0)
                         (conj row3 (+ l-neg2 (* value k-neg2)))))
                [row row2 row3]))))
          ;; push the buffers into the matrices
        (do (cv/set-values img 0 0 (double-array buffer))
          (cv/set-values img2 0 0 (double-array buffer2))
          (cv/set-values img3 0 0 (double-array buffer3)))))
    ;; upscale the matrices back to the desired size
    [(cv/resize img [(make-odd (int (* pos-width div)))
                     (make-odd (int (* pos-width div)))])
     (cv/resize img2 [(make-odd (int (* width div)))
                      (make-odd (int (* width div)))])
     (cv/resize img3 [(make-odd (int (* width div)))
                      (make-odd (int (* width div)))])]))

;;;;
;; See the function make-mex-hat-image above. This function is similar, but it
;; makes rectangular hats. The width and the height of the hat image are specified separately.
;; Additinally, pt0 and p1 are provided. These points specify the two centers of the rectangular
;; enhanced region. The amount of enhancement or suppression at any point in the image is determined
;; based on the distance from that point to the line segment [pt0 pt1].

;;NOTE!! Rectangular Mexican hats are not used currently.
(defn make-mex-hat-image-rect
  "Returns a rectangular 2D Mexican hat image as an OpenCV matrix."
  ([init-width init-height k w l k-neg w-neg l-neg pt0 pt1 params]
   (let [half-width (int (/ init-width 2))
         half-height (int (/ init-height 2))
         width (+ 1 (* half-width 2))
         height (+ 1 (* half-height 2))
         img (cv/new-java-mat [width height] cv/CV_32FC1)]
     ;;     (println :image "Making rectangular mex hat..." width height w w-neg)
     (loop [row-idx 0
            buffer (vector-of :double)]
       (if (< row-idx height)
         (recur
           (inc row-idx)
           ;; add row values to buffer
           (loop [col-idx 0
                  row buffer]
             (let [i (math/sqrt (vec/pt-lineseg-sq-dist
                                 [(- col-idx half-width) (- row-idx half-height)] pt0 pt1))
                   value (mex-hat (if (< i w)
                                    (/ i w)
                                    (+ 1 (/ (- i w) w-neg)))
                                  1)]
               (if (< col-idx width)
                 (recur (inc col-idx)
                        ;; add value to row
                        (if (> value 0)
                          (conj row (+ l (* value k)))
                          (conj row (+ l-neg (* value k-neg)))))
                 row))))
         ;; push the buffer into the matrix
         (.put img 0 0 (double-array buffer))))
     img))
  ;;The code below is used when the optional parameters are passed.
  ([init-width init-height k w l k-neg w-neg l-neg pt0 pt1 k2 l2 k-neg2 l-neg2 params]
   (let [div (get-mexhat-divisor w params)
         half-width (int (/ init-width (* 2 div)))
         half-height (int (/ init-height (* 2 div)))
         w (/ w div)
         w-neg (/ w-neg div)
         pt0 (mapv #(/ % div) pt0)
         pt1 (mapv #(/ % div) pt1)
         width (+ 1 (* half-width 2))
         height (+ 1 (* half-height 2))
         img (cv/new-java-mat [width height] cv/CV_32FC1)
         img2 (cv/new-java-mat [width height] cv/CV_32FC1)]
     (loop [row-idx 0
            ;;Uses two buffers for the two Mexican hat images
            [buffer buffer2] [(vector-of :double) (vector-of :double)]]
       (if (< row-idx height)
         (recur
           (inc row-idx)
           ;; add row values to buffers
           (loop [col-idx 0
                  row buffer
                  row2 buffer2]
             (let [i (math/sqrt (vec/pt-lineseg-sq-dist
                                 [(- col-idx half-width) (- row-idx half-height)] pt0 pt1))
                   value (mex-hat (if (< i w)
                                    (/ i w)
                                    (+ 1 (/ (- i w) w-neg)))
                                  1)]
               (if (< col-idx width)
                 (recur (inc col-idx)
                        ;; add value to row
                        (if (> value 0)
                          (conj row (+ l (* value k)))
                          (conj row (+ l-neg (* value k-neg))))
                        (if (> value 0)
                          (conj row2 (+ l2 (* value k2)))
                          (conj row2 (+ l-neg2 (* value k-neg2)))))
                 [row row2]))))
         ;; push the buffers into the matrices
         (do (cv/set-values img 0 0 (double-array buffer))
             (cv/set-values img2 0 0 (double-array buffer2)))))

     ;; upscale the matrices back to the desired size
     [(cv/resize img [(make-odd (int (* width div))) (make-odd (int (* height div)))])
      (cv/resize img2 [(make-odd (int (* width div))) (make-odd (int (* height div)))])])))

;;;;
;; Makes two mexhat images as above, but follows certain scaling rules.
;; a) Positive region is 0 to 1 (same as above)
;; b) Negative region is 1 to 3
;;
;; Note that w and w-neg should be integers.
;;
;; Also automatically determines the radius of the image.
;;
;; If pt0 and p1 are specified, make a rectangular image whose center
;; is the line segment connecting these two points.
;;
;; The final four arguments pased to make-mex-hat-image-set or make-mex-hat-image-rect
;; specify the amplitude of the second mexhat image, which will have 0 positive
;; amplitude and a small negative amplitude determined by small-hat-muli.
(defn make-scaled-mex-hat-image
  "Returns two 2D Mexican hat images as OpenCV matrices scaled so that the
  negative region is between 1 and 3."
  ([k w l k-neg w-neg l-neg params]
   (make-mex-hat-image-set (int (+ w (* w-neg 1.75))) k w l k-neg (/ w-neg 2) l-neg
                       (:small-hat-k params) 0 params))

  ([k w l k-neg w-neg l-neg pt0 pt1 params]
   (let [buffer (+ 1 (* 2 (+ w (* w-neg 1.75))))]
     (make-mex-hat-image-rect (int (+ buffer (- (first pt0) (first pt1))))
                              (int (+ buffer (- (second pt0) (second pt1))))
                              k w l k-neg (/ w-neg 2) l-neg pt0 pt1
                              0 0 (:small-hat-k params) 0 params))))

(defn make-bias-image
  "Constructs a small positive region of a Mexican hat image."
  [radius value]
  (make-mex-hat-image radius value radius 0 0 1 0))

;;NOTE!!! This is for rectangular Mexican hats, whicha are not used currently.
(defn- mh-index->lineseg-pts
  "Returns two points [x y] to define the line segment lying at the center of
  the Mexican hat for a particular Mexican hat index. The points are nil of the
  Mexican hat is circular and not rectangular."
  [[small-diameter big-diameter aspect-ratio dimension]]
  (if dimension
    (let [half-width (/ small-diameter 2)
          center-point-dist (- (/ half-width aspect-ratio) half-width)]
      [(mapv #(int (* % center-point-dist)) dimension)
       (mapv #(int (* % (- center-point-dist))) dimension)])
    [nil nil]))

;;;;
;; This helper function allows mh-index->mexhats to pass it a list of distances from
;; the center of gaze along with an index i. The function determines the average distance
;; for the particular index i and then passes this information along to make-scaled-mex-hat-image.
(defn- make-mex-hats-helper
  "Returns two 2D Mexican hat images whose negative region is scaled based on distance
  from the center of gaze."
  [dist-range radius pt0 pt1 i params]
  (let [half-avg-dist (/ (+ (get dist-range i) (get dist-range (+ i 1))) 4)]
    (make-scaled-mex-hat-image (:hat-k params) radius (:hat-l params)
                               (:hat-neg-k params) half-avg-dist (:hat-neg-l params) params)))


;;;;
;; Constructs a set of Mexican hats for a region's particular mexican hat index (mhi). A
;; different hat should be used depending on the region's distance from the gaze center.
;; Builds both "big" hats, which have both positive and negative regions, and "small" hats,
;; which have only negative regions.
;;
;; Computes a separate hat for the bias image, which is an extra bit of enhancement at the locus of
;; the attended object.
(defn- mh-index->mexhats
  "Returns a vector describing a set of Mexican hats for a particular Mexican hat index."
  [[small-diameter big-diameter aspect-ratio dimension] [x y] params]
  (let [[pt0 pt1] (mh-index->lineseg-pts [small-diameter big-diameter aspect-ratio dimension])
        half-width (/ small-diameter 2)
        num-hats (:num-hats params)
        max-x (/ x 2)
        max-y (/ y 2)
        radius (* (:hat-pos-radius-multi params) half-width)
        dist-range (apply vector (map #(int (dist-from-origin (* max-x (/ % num-hats))
                                                              (* max-y (/ % num-hats))))
                                      (range (+ num-hats 1))))
        hat-sets (map #(make-mex-hats-helper dist-range radius pt0 pt1 % params) (range num-hats))

        bias-radius (int (* half-width (:pos-bias-radius-multi params)))]

    ;;The information returned for a region's Mexican hats.
    {:enhance-radius radius
     :big-pos-hats (zipmap (butlast dist-range) (map first hat-sets))
     :big-neg-hats (zipmap (butlast dist-range) (map second hat-sets))
     :small-neg-hats (zipmap (butlast dist-range) (map #(get % 2) hat-sets))
     :bias-radius bias-radius
     :bias-image (make-bias-image bias-radius (:pos-bias-strength-multi params))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for mapping between regions and Mexican hats
;;
;; Regions are mapped to Mexican hat indexes
;; Mexican hat indexes can compared to determine whether a region can use
;; an existing mexhat, or whether a new mexhat needs to be computed.

;;;;
;; This function can take a region or a list in which the first element is the region.
(defn- region->mh-index
  "Given a region, returns the index that will be used to retrieve
  the appropriately sized/shaped mexican hat for it."
  [region-or-list params]
  (let [region (if (seq? region-or-list)
                 (first region-or-list)
                 region-or-list)
        w (:width region)
        h (:height region)
        aspect-ratio (/ (min w h) (max w h))]
    (vector (min w h) (max w h) aspect-ratio
            (when (> (- 1 aspect-ratio) (:ar-thresh params))
              (if (> w h)
                [1 0]
                [0 1])))))

(defn- mh-indices-different?
  "Determines whether two mexican mat indexes should correspond to
  different mexican hats."
  [[w0 h0 ar0 dim0] [w1 h1 ar1 dim1] params]
  (or (> (- 1 (/ (min w0 w1) (max w0 w1))) (:width-thresh params))
      (> (- 1 (/ (min h0 h1) (max h0 h1))) (:width-thresh params))
      (> (- (max ar0 ar1 (min ar0 ar1))) (:ar-thresh params))
      (not (= dim0 dim1))))

(defn make-mexhats-for-regions
  "For each region in regions, make sure there is a set of mexican hats
   in mexhats that matches it. If not, add one. Returns the updated mexhats."
  [regions mexhats dims params]
  (if (empty? regions)
    mexhats
    (let [mhi (region->mh-index (first regions) params)]
      (if (some #(not (mh-indices-different? mhi % params)) (keys mexhats))
        (make-mexhats-for-regions (rest regions) mexhats dims params)
        (make-mexhats-for-regions (rest regions)
                                  (assoc mexhats mhi (mh-index->mexhats mhi dims params)) dims params)))))

(defn get-pos-mexhat-for-region
  "Retrieves a region's big, positive Mexican hat from mexhats."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))
        hats (get (get mexhats mhi-key) :big-pos-hats)]
    (first (vals hats))))
;;       (get hats (seek #(>= dist %) (reverse (keys hats))))))

(defn get-neg-mexhat-for-region
  "Retrieves a region's Mexican hat from mexhats, given big? (get a big hat or small)
    and the distance from the gaze center."
  [region mexhats big? dist params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))
        hats (if big?
               (get (get mexhats mhi-key) :big-neg-hats)
               (get (get mexhats mhi-key) :small-neg-hats))]
    (get hats (g/seek #(>= dist %) (reverse (keys hats))))))

(defn get-mexhat-radius-for-region
  "Retrieves a region's enhanced region radius from mexhats."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))]
    (get (get mexhats mhi-key) :enhance-radius)))

(defn get-bias-for-region
  "Retrieves a region's bias Mexican hat image from mexhats."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))]
    (get (get mexhats mhi-key) :bias-image)))

(defn get-bias-radius-for-region
  "Retrieves a region's bias radius from mexhats."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))]
    (get (get mexhats mhi-key) :bias-radius)))

(defn get-enhance-bias-radius-for-region
  "Retrieves a region's enhanced region radius and bias radius from mexhats."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))
        mexhat (get mexhats mhi-key)]
    [(get mexhat :enhance-radius) (get mexhat :bias-radius)]))

(defn get-center-for-region
  "Returns the center of the region."
  [region]
  (if (seq? region)
    (reg/center (first region))
    (reg/center region)))

(defn get-bias-center-for-region
  "Returns the center of the bias for a region, if one exist."
  [region]
  (when (seq? region)
    (reg/center (second region))))

(defn get-mexhat-lineseg-for-region
  "Computes the points for a line segment lying at the center of the region's
  enhanced region. Returns nil if the associated Mexican hat is not rectangular."
  [region mexhats params]
  (let [mhi (region->mh-index region params)
        mhi-key (g/seek #(not (mh-indices-different? % mhi params)) (keys mexhats))
        mexhat (get mexhats mhi-key)
        [pt0 pt1] (mh-index->lineseg-pts mhi-key)
        center (get-center-for-region region)]
    (if (seq pt0)
      [(mapv + pt0 center)
       (mapv + pt1 center)]
      [nil nil])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for computing a positive Mexican hat's current region, given its
;; location on the object location map.
;;
;; Regions are mapped to Mexican hat indexes
;; Mexican hat indexes can be compared to determine whether a region can use
;; an existing mexhat, or whether a new mexhat needs to be computed.

(defn- update-hat-with-object-location-map
  "Make a new image by adding the hat to the portion of olmap where the hat is located."
  [hat xy olmap]
  (let [[x y] (mapv int xy)
        w-radius (/ (- (cv/width hat) 1) 2)
        h-radius (/ (- (cv/height hat) 1) 2)

        old-min-x (- x w-radius)
        old-min-y (- y h-radius)
        old-max-x (+ x w-radius)
        old-max-y (+ y h-radius)

        min-x (max old-min-x 0)
        min-y (max old-min-y 0)
        max-x (min old-max-x (dec (cv/width olmap)))
        max-y (min old-max-y (dec (cv/height olmap)))

        hat-min-x (- min-x old-min-x)
        hat-min-y (- min-y old-min-y)
        hat-width (- (cv/width hat) hat-min-x
                     (- old-max-x max-x))
        hat-height (- (cv/height hat) hat-min-y
                      (- old-max-y max-y))]
    (when (and (pos? hat-width) (pos? hat-height))
      [(cv/add (cv/submat hat hat-min-x hat-min-y
                          hat-width hat-height)
               (cv/submat olmap min-x min-y
                          hat-width hat-height))
       hat-min-x hat-min-y])))

(defn hat->enhanced-region
  "Computes the updated region for an enhanced region given the suppression in
  the current object location map. Also provides the image of the enhanced region
  with added suppression as a second return value, so that it can be displayed."
  [hat {cx :x cy :y} olmap]
  (let [[src x-plus y-plus] (update-hat-with-object-location-map hat [cx cy] olmap)
        mask (-> src (cv/threshold 0 cv/THRESH_BINARY :max-value 255)
                 (cv/convert-to cv/CV_8UC1))]
    {:hat src :mask mask
     :region (-> mask cv/non-zero-bounds
                 (reg/translate {:x (- cx (/ (cv/width hat) 2) x-plus)
                                 :y (- cy (/ (cv/height hat) 2) y-plus)})
                 reg/prepare)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions for building object-location maps

(defn initialize-object-location-matrix
  "Set up an OpenCV matrix for use as an object-location map."
  [width height]
  (cv/zeros [(int width) (int height)] cv/CV_32FC1))

(defn make-object-location-map
  "Construct a object location map by placing multiple Mexican hat images on
  an overall map of a scene. foci and mask-images are lists of locations and
  (optional) masks that correspond to each Mexican hat. If base is a number, then
  reset the matrix to that number at all locations before placing the hats. If
  add? is true, then add the Mexican hats to the image. Otherwise, copy them to
  the image."
  [mat foci mexican-hats mask-images base add?]
  (when (number? base)
    (cv/set-to mat base))

  (loop [pts foci
         hats mexican-hats
         masks mask-images]

    (if (not (seq pts))
      mat
      (let [{x :x y :y} (first pts)
            hat (first hats)
            mask (first masks)

            old-min-x (int (- x (/ (dec (cv/width hat)) 2)))
            old-min-y (int (- y (/ (dec (cv/height hat)) 2)))
            old-max-x (+ old-min-x (dec (cv/width hat)))
            old-max-y (+ old-min-y (dec (cv/height hat)))

            min-x (max old-min-x 0)
            min-y (max old-min-y 0)
            max-x (min old-max-x (dec (cv/width mat)))
            max-y (min old-max-y (dec (cv/height mat)))

            hat-min-x (- min-x old-min-x)
            hat-min-y (- min-y old-min-y)
            hat-width (- (cv/width hat) hat-min-x
                         (- old-max-x max-x))
            hat-height (- (cv/height hat) hat-min-y
                          (- old-max-y max-y))]
        (when (and (pos? hat-width) (pos? hat-height))
          (let [src (cv/submat hat hat-min-x hat-min-y hat-width hat-height)
                mask (some-> mask
                             (cv/submat hat-min-x hat-min-y hat-width hat-height))
                dst (cv/submat mat min-x min-y hat-width hat-height)]
            (if add?
              (cv/add src dst :dst dst :mask mask)
              (cv/copy src :dst dst :mask mask))))
        (recur (rest pts)
               (rest hats)
               (rest masks))))))
