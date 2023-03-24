(ns
  ^{:author "Andrew Lovett"
    :doc
    "Provides support for representing, operating over, and visualizing feature
     maps and feature histograms.

     Feature maps and feature histograms are represened as hashmaps with
     the following fields:
     :matrix - An opencv matrix containing the data
     :matrix-type - A symbol, which should be :feature-map or :feature-histogram
     :feature-type - A symbol, which should be one of the following
       :magnitude - This feature ranges from 0 to 1.
       :difference - This feature ranges from -1 to 1.
       :direction - This feature ranges from 0 to 360 and represents direction
                    in degrees.
       :orientation - This feature ranges from to 0 to 360 and represents an
                      orientation in degrees. Divide by 2 to get the orientation,
                      since orientations actually only range from 0 to 180 degrees.
       :quantity - This feature can have any value. Because it is not normed, there
                   is no good way to visualize it.
     :intensity - An optional second matrix. When a feature map or histogram represents a
                  value, for example color hue, it is helpful to encode the
                  corresponding intensity, for example saturation*brightness at
                  every location."}
  arcadia.vision.features
  ; (:use clojure.math.numeric-tower arcadia.utility.display arcadia.utility.general)
  (:refer-clojure :exclude [name])
  (:require [arcadia.utility.general :as g]
            [arcadia.utility.opencv :as cv]
            [clojure.string :refer [capitalize]]))

; (def ^:private intensity-wts
;   "1x20 matrix of weights that increase from 0.025 to 0.972."
;   (->> (range 20) (map #(+ (/ % 20) (/ 1 40))) float-array cv/->java))

(def ^:private intensity-wts
  "1x20 matrix of weights that increase from 0.0 to 0.95."
  (->> (range 20) (map #(/ % 20)) float-array cv/->java))

(def ^:private background-color
  "Color of the background around visualizations."
  230)

(def ^:private line-color
  "Color of lines drawn for visualizations."
  0)

(def ^:private dim-line-color
  "Color of dim lines drawn for visualizations."
  180)

(def ^:private line-width
  "Thickness of lines drawn for visualizations."
  2)

(def ^:private tick-width
  "Thickness of ticks drawn on axes for visualizations."
  1)

(def ^:private deg->pi (/ Math/PI 180))
(def ^:private pi->deg (/ 180 Math/PI))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Support for color conversion.

(defn- interpolate-colors
  "Assists in generating a lookup table. Takes an array of length 256 representing
   the output. Iterates through this array from y1 to y2 and fills in its values
   by interpolating from start to end."
  [output y1 y2 start end]
  (loop [i y1]
    (let [perc (float (/ (- i y1) (- y2 y1)))]
      (aset output i (Math/round (+ start (* (- end start) perc)))))

    (if (>= i y2)
      output
      (recur (inc i)))))

(def ^:private arr-hue-to-opp
  (let [arr (int-array 256)]
    (interpolate-colors arr 0 30 0 45)
    (interpolate-colors arr 30 60 45 90)
    (interpolate-colors arr 60 120 90 135)
    (interpolate-colors arr 120 180 135 180)
    (interpolate-colors arr 180 255 180 180)
    arr))

(def ^:private arr-opp-to-hue
  (let [arr (int-array 256)]
    (interpolate-colors arr 0 45 0 30 )
    (interpolate-colors arr 45 90 30 60)
    (interpolate-colors arr 90 135 60 120)
    (interpolate-colors arr 135 180 120 180)
    (interpolate-colors arr 180 255 180 180)
    arr))

(def LUT-hue-to-opp
  "Lookup table used to rescale an 8-bit hue matrix (as found in an HSV image),
   such that R/G and B/Y are opposite each other on the color wheel."
  (cv/create-lookup-table arr-hue-to-opp :table-type cv/CV_8U))

(def LUT-opp-to-hue
  "Lookup table used to rescale an 8-bit hue matrix (as found in an HSV image),
   going from a scale where R/G and B/Y are opposite each other on the color wheel
   to the typical scale for HSV color space (where red and cyan are opposites)."
  (cv/create-lookup-table arr-opp-to-hue :table-type cv/CV_8U))

(def LUT-hsv-to-opp
  "Lookup table used to rescale the hue channel of an 8-bit HSV image, such that
   R/G and B/Y are opposite each other on the color wheel."
  (cv/create-lookup-table arr-hue-to-opp :table-type cv/CV_8U
                          :G (int-array (range 256)) :R (int-array (range 256))))

(def LUT-opp-to-hsv
  "Lookup table used to rescale the hue channel of an 8-bit HSV image, going from
   a scale where R/G and B/Y are opposite each other on the color wheel to the
   typical scale for HSV color space (where red and cyan are opposites)."
  (cv/create-lookup-table arr-opp-to-hue :table-type cv/CV_8U
                          :G (int-array (range 256)) :R (int-array (range 256))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns for visualizing feature maps and histograms.

(def magnitude-colormap
  "colormap for formatting :magnitude feature maps (ranging from 0 to 1)"
  (-> (range 256) int-array cv/->java (cv/convert-to cv/CV_8U)
      (cv/apply-colormap cv/COLORMAP_BONE)))

(def difference-colormap
  "colormap for formatting :difference feature maps (ranging from -1 to 1)"
  (cv/vconcat
   (-> (range 256) int-array cv/->java (cv/multiply 2.0) (cv/submat {:y 1 :height 112})
       (cv/copy-make-border 0 15 0 0 (+ cv/BORDER_REPLICATE cv/BORDER_ISOLATED))
       (cv/convert-to cv/CV_8U) (cv/apply-colormap cv/COLORMAP_HOT) (cv/flip 0))
   (-> (range 256) int-array cv/->java (cv/multiply 2.0) (cv/submat {:y 0 :height 129})
       (cv/convert-to cv/CV_8U) (cv/apply-colormap cv/COLORMAP_BONE))))

(defn magnitude-axis
  "Returns an image representing a legend for a :magnitude feature-map or an
   x-axis for a :magnitude histogram. width is the desired width of the image.
   margin is an optional keyword parameter indicating how much space there should
   be between the leftmost point of the image and the leftmost point of the axis
   drawn onto the image."
  [width & {:keys [margin] :or {margin (Math/ceil (/ width 10))}}]

  (let [xm margin ;;x margin
        ym margin;(Math/floor (* margin 1.5)) ;;y margin
        colormap-width (- width (* xm 2))
        colormap-height xm
        height (+ colormap-height ym)
        canvas (cv/new-mat magnitude-colormap
                           :size {:width width :height height} :value background-color)
        x0 xm
        x1 (int (+ xm (/ colormap-width 4)))
        x2 (int (+ xm (/ colormap-width 2)))
        x3 (int (+ xm (* colormap-width 0.75)))
        x+ (+ xm colormap-width)
        yc (+ colormap-height (int (/ ym 2))) ;;center y value for lines
        radius (Math/floor (/ ym 3))] ;;radius for lines
    (cv/set-to! (cv/submat canvas xm 0 colormap-width colormap-height)
               (-> magnitude-colormap cv/transpose
                   (cv/resize {:width colormap-width :height colormap-height})))

    ;;0
    (cv/line! canvas {:x x0 :y colormap-height} {:x x0 :y yc}
              line-color :thickness tick-width)
    ;;0.25
    (cv/line! canvas {:x x1 :y colormap-height} {:x x1 :y (+ colormap-height radius)}
              line-color :thickness tick-width)

    ;;0.5
    (cv/line! canvas {:x x2 :y colormap-height} {:x x2 :y yc}
              line-color :thickness tick-width)

    ;;0.75
    (cv/line! canvas {:x x3 :y colormap-height} {:x x3 :y (+ colormap-height radius)}
              line-color :thickness tick-width)

    ;;+1
    (cv/line! canvas {:x (- x+ radius) :y yc} {:x (+ x+ radius) :y yc}
              line-color :thickness tick-width)
    (cv/line! canvas {:x x+ :y (- yc radius)} {:x x+ :y (+ yc radius)}
              line-color :thickness tick-width)
    canvas))

(defn difference-axis
  "Returns an image representing a legend for a :difference feature-map  or an
   x-axis for a :difference histogram. width is the desired width of the image.
   margin is an optional keyword parameter indicating how much space there should
   be between the leftmost point of the image and the leftmost point of the axis
   drawn onto the image."
  [width & {:keys [margin] :or {margin (Math/ceil (/ width 10))}}]

  (let [xm margin ;;x margin
        ym margin;(Math/floor (* margin 1.5)) ;;y margin
        colormap-width (- width (* xm 2))
        colormap-height xm
        height (+ colormap-height ym)
        canvas (cv/new-mat difference-colormap
                           :size {:width width :height height} :value background-color)
        x-- xm
        x- (int (+ xm (/ colormap-width 4)))
        x0 (int (+ xm (/ colormap-width 2)))
        x+ (int (+ xm (* colormap-width 0.75)))
        x++ (+ xm colormap-width)
        yc (+ colormap-height (int (/ ym 2))) ;;center y value for lines
        radius (Math/floor (/ ym 3))] ;;radius for lines
    (cv/set-to! (cv/submat canvas xm 0 colormap-width colormap-height)
               (-> difference-colormap cv/transpose
                   (cv/resize {:width colormap-width :height colormap-height})))

    ;;-1
    (cv/line! canvas {:x (- x-- radius) :y yc} {:x (+ x-- radius) :y yc}
              line-color :thickness tick-width)

    ;;-0.5
    (cv/line! canvas {:x x- :y colormap-height} {:x x- :y (+ colormap-height radius)}
              line-color :thickness tick-width)

    ;;0
    (cv/line! canvas {:x x0 :y colormap-height} {:x x0 :y yc}
              line-color :thickness tick-width)

    ;;0.5
    (cv/line! canvas {:x x+ :y colormap-height} {:x x+ :y (+ colormap-height radius)}
              line-color :thickness tick-width)

    ;;+1
    (cv/line! canvas {:x (- x++ radius) :y yc} {:x (+ x++ radius) :y yc}
              line-color :thickness tick-width)
    (cv/line! canvas {:x x++ :y (- yc radius)} {:x x++ :y (+ yc radius)}
              line-color :thickness tick-width)
    canvas))

(defn direction-axis
  "Returns an image representing a legend for a :direction feature-map or an x-axis
   for a :direction histogram. width is the desired width of the image. margin is
   an optional keyword parameter indicating how much space there should be between
   the leftmost point of the image and the leftmost point of the axis drawn onto
   the image."
  [width & {:keys [margin] :or {margin (Math/ceil (/ width 10))}}]

  (let [xm margin ;;x margin
        height (* xm 2)
        axis-width (- width (* xm 2))
        y (int (/ height 2))
        radius (Math/floor (/ height 5))
        canvas (cv/new-mat magnitude-colormap
                           :size {:width width :height height} :value [0 0 background-color])]

    (dotimes [i 9]
      (let [multi (/ i 8.0)
            angle-deg (* 360.0 multi)
            angle (* angle-deg deg->pi)
            x (int (+ xm (* multi axis-width)))]
        (cv/arrowed-line!
         canvas
         {:x (- x (-> angle Math/cos (* radius) Math/round))
          :y (- y (-> angle Math/sin (* radius) Math/round))}
         {:x (+ x (-> angle Math/cos (* radius) Math/round))
          :y (+ y (-> angle Math/sin (* radius) Math/round))}
         [(* angle-deg 0.5) 255 255] :thickness line-width :tip-length 0.4)))
    (cv/apply-lookup-table! canvas LUT-opp-to-hsv)
    (cv/cvt-color! canvas cv/COLOR_HSV2BGR)))

(defn orientation-axis
  "Returns an image representing a legend for an :orientation feature-map or an
   x-axis for a value histogram. width is the desired width of the image. margin
   is an optional keyword parameter indicating how much space there should be
   between the leftmost point of the image and the leftmost point of the axis
   drawn onto the image."
  [width & {:keys [margin] :or {margin (Math/ceil (/ width 10))}}]

  (let [xm margin
        height (* xm 2)
        axis-width (- width (* xm 2))
        y (int (/ height 2))
        radius (Math/floor (/ height 5))
        canvas (cv/new-mat magnitude-colormap
                           :size {:width width :height height} :value [0 0 background-color])]

    (dotimes [i 9]
      (let [multi (/ i 8.0)
            angle-deg (* 360.0 multi)
            angle (* angle-deg (/ Math/PI 180.0) 0.5)
            x (int (+ xm (* multi axis-width)))
            cos (Math/cos angle)
            sin (Math/sin angle)]
        (cv/line!
         canvas
         {:x (- x (-> angle Math/cos (* radius) Math/round))
          :y (- y (-> angle Math/sin (* radius) Math/round))}
         {:x (+ x (-> angle Math/cos (* radius) Math/round))
          :y (+ y (-> angle Math/sin (* radius) Math/round))}
         [(* angle-deg 0.5) 255 255] :thickness line-width)
        ))
    (cv/apply-lookup-table! canvas LUT-opp-to-hsv)
    (cv/cvt-color! canvas cv/COLOR_HSV2BGR)))

(defn blank-axis
  "Returns a blank rectangle the same size as a typical axis, as might be returned
   for example, by magnitude-axis."
  [width & {:keys [margin] :or {margin (Math/ceil (/ width 10))}}]
  (cv/new-mat magnitude-colormap :size {:width width :height (* margin 2)} :value background-color))

(defn axis
  "Calls the appropriate axis function for the feature-type of a feature-map or
   histogram, or calls blank-axis if there is no feature-type. Uses the same args
   as functions like magnitude-axis."
  [{ftype :feature-type} & args]
  (case ftype
    :magnitude (apply magnitude-axis args)
    :difference (apply difference-axis args)
    :direction (apply direction-axis args)
    :orientation (apply orientation-axis args)
    (apply blank-axis args)))

(defn visualize-feature-map
  "Formats a feature map so that it can be displayed."
  [{src :matrix ftype :feature-type :as fmap}]
  (case ftype
    :magnitude
    (-> (cv/convert-to src cv/CV_8U :alpha 255.0)
        (cv/apply-colormap magnitude-colormap))
    :difference
    (-> (cv/add src 1.0) (cv/convert-to cv/CV_8U :alpha 127.49)
        (cv/apply-colormap difference-colormap))
    (:direction :orientation)
    (-> (cv/divide src 2)
        (cv/merge (cv/new-mat src :value 255) ;;Saturation
                  (or (some-> (:intensity fmap) (cv/multiply 255))
                      (cv/new-mat src :value 255)))
        (cv/convert-to cv/CV_8U) (cv/apply-lookup-table LUT-opp-to-hsv)
        (cv/cvt-color cv/COLOR_HSV2BGR))))

(defn y-axis
  [width height]
  (let [canvas (cv/new-mat magnitude-colormap
                           :size {:width width :height height} :value background-color)
        ym 4
        xm 4
        w (- width (* xm 2))
        h (- height (* ym 2))
        x50 (+ xm (int (/ w 2)))
        y75 (+ ym (int (* h 0.25)))
        y50 (+ ym (int (* h 0.50)))
        y25 (+ ym (int (* h 0.75)))]

    ;1
    (cv/line! canvas {:x xm :y ym} {:x (+ xm w) :y ym}
              line-color :thickness tick-width)

    ;0.75
    (cv/line! canvas {:x x50 :y y75} {:x (+ xm w) :y y75}
              line-color :thickness tick-width)

    ;0.50
    (cv/line! canvas {:x xm :y y50} {:x (+ xm w) :y y50}
              line-color :thickness tick-width)

    ;0.25
    (cv/line! canvas {:x x50 :y y25} {:x (+ xm w) :y y25}
              line-color :thickness tick-width)

    ;0
    (cv/line! canvas {:x xm :y (- height ym)} {:x (+ xm w) :y (- height ym)}
              line-color :thickness tick-width)

    canvas))

(defn visualize-histogram
  [hist & {:keys [width height margin] :or {width 500 height 300
                                            margin (Math/ceil (/ width 10))}}]
  (let [{src :matrix intensity :intensity} hist
        bins (cv/height src)
        bins- (dec bins)
        xm margin
        ym 4
        axis-width- (- width (* xm 2) 1)
        axis-height- (- height (* ym 2) 1)
        bin-width (/ axis-width- bins-)
        canvas (cv/new-mat magnitude-colormap :size {:width (inc axis-width-) :height height}
                           :value background-color)
        ys (-> (cv/subtract (cv/ones src) src) (cv/multiply axis-height-)
               (cv/convert-to cv/CV_32S)
               (cv/add ym))
        i-ys (when intensity
               (-> (cv/subtract (cv/ones src) intensity) (cv/multiply axis-height-)
                   (cv/convert-to cv/CV_32S)
                   (cv/add ym)))]

    ;;Draw a line along y = 0
    ; (cv/line! canvas {:x 0 :y (+ ym axis-height-)}
    ;           {:x axis-width- :y (+ ym axis-height-)}
    ;           dim-color :thickness 2)

    ;;Add tick marks for the y-axis at 0.25, 0.5, 0.75 and 1

    (when i-ys
      (dotimes [idx bins-]
        (cv/line!
         canvas
         {:x (* idx bin-width)
          :y (cv/get-value i-ys 0 idx)}
         {:x (* (inc idx) bin-width)
          :y (cv/get-value i-ys 0 (inc idx))}
         dim-line-color :thickness 2)))

    (dotimes [idx bins-]
      (cv/line!
       canvas
       {:x (* idx bin-width)
        :y (cv/get-value ys 0 idx)}
       {:x (* (inc idx) bin-width)
        :y (cv/get-value ys 0 (inc idx))}
       line-color :thickness 2))

    (-> (cv/hconcat (y-axis margin height) canvas
                    (cv/new-mat magnitude-colormap :size {:width margin :height height}
                                :value background-color))
        (cv/vconcat (axis hist width :margin margin)))))

(defn visualize
  "Formats a feature map or histogram so that it can be displayed."
  [src]
  (case (:matrix-type src)
    :feature-map (visualize-feature-map src)
    :feature-histogram (visualize-histogram src)
    src))

(defn visualizable?
  "Returns true if src is something that can be visualized (a feature map or
   feature histogram)."
  [src]
  (case (:matrix-type src)
    :feature-map true
    :feature-histogram true
    false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns for processing feature-maps

; (def gk (cv/get-gaussian-kernel 31))
; (dotimes [i 15]
;   (cv/set-value! gk 0 (+ i 8) 0.0))
;
; (cv/divide! gk (cv/sum-elems gk))

(defn make-gaussian-kernel [width center-width]
  (let [gk (cv/get-gaussian-kernel width)]
    (cv/set-values! gk {:x 0 :y (-> (- width center-width) (/ 2) int)}
                   (float-array center-width 0))
    (cv/divide! gk (cv/sum-elems gk))))

(defn contrast-map
  "Compute a contrast map, given a feature map and a contrast width."
  [{src :matrix :as fmap} & {:keys [contrast-width center-width kernel-1d kernel-2d
                                    weight-weight] :or {weight-weight 1}}]
  (let [gauss-fn (cond
                   kernel-1d #(cv/sep-filter-2D % kernel-1d kernel-1d)

                   (and contrast-width center-width)
                   (let [kernel (make-gaussian-kernel contrast-width center-width)]
                     #(cv/sep-filter-2D % kernel kernel))

                   :else
                   #(cv/gaussian-blur % {:width contrast-width :height contrast-width}))

        mean-surround (when (< weight-weight 1) (gauss-fn src))
        weighted-surround (when (> weight-weight 0)
                            (let [asrc (cv/abs src)]
                            (-> src (cv/multiply asrc) gauss-fn
                                (cv/divide! (gauss-fn asrc)))))]
    {:feature-type :difference :matrix-type :feature-map :matrix
     (cond (and mean-surround weighted-surround)
       (cv/subtract src (cv/add-weighted! weighted-surround weight-weight mean-surround (- 1 weight-weight)))
       mean-surround (cv/subtract src mean-surround)
       :else (cv/subtract src weighted-surround))}))
  ; {:feature-type :difference :matrix-type :feature-map :matrix
  ;  (cv/subtract src (cv/gaussian-blur src {:width contrast-width :height contrast-width}))})

(defn enhance-map
  "Computes a feature enhancement map, given a feature map and a contrast width."
  [{src :matrix intensity :intensity :as fmap} enhancement]
  {:feature-type :difference :matrix-type :feature-map :matrix
   (cond-> (cv/apply-lookup-table src enhancement)
           intensity (cv/multiply! intensity))})

; (defn new-map
;   "Compute a contrast map, given a feature map and a contrast width."
;   [{src :matrix :as fmap} contrast-width]
;   {:feature-type :difference :matrix-type :feature-map :matrix
;    (some-> src (cv/multiply (cv/abs src)) (cv/gaussian-blur {:width 15 :height 15})
;            (cv/divide (cv/gaussian-blur (cv/abs src) {:width 15 :height 15})))})
;
; (defn new-map
;   "Compute a contrast map, given a feature map and a contrast width."
;   [{src :matrix :as fmap} contrast-width]
;   {:feature-type :difference :matrix-type :feature-map :matrix
;    (some-> src (cv/multiply (cv/abs src)) (cv/sep-filter-2D gk gk)
;            (cv/divide (cv/sep-filter-2D (cv/abs src) gk gk)))})
;
; (defn new-map2
;   "Compute a contrast map, given a feature map and a contrast width."
;   [{src :matrix :as fmap} contrast-width]
;   {:feature-type :difference :matrix-type :feature-map :matrix
;    (cv/subtract src (:matrix (new-map fmap contrast-width)))})

; (defn new-map2
;   "Compute a contrast map, given a feature map and a contrast width."
;   [{src :matrix :as fmap} contrast-width]
;   {:feature-type :difference :matrix-type :feature-map :matrix
;    (cv/subtract src (cv/sep-filter-2D src gk gk))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns getting information about feature maps and histograms.

(defn feature-map?
  "Returns true if fmap is a feature map."
  [fmap]
  (= (:matrix-type fmap) :feature-map))

(defn feature-histogram?
  "Returns true if hist is a feature histogram."
  [hist]
  (= (:matrix-type hist) :feature-histogram))

(defn name-string
  "Returns the name-string for a feature-map"
  [{name :name multi :magnification :as fmap}]
  (let [suffix (if multi (format " (%.1fx)" (float multi)) "")]
    (when-let [name (some-> name symbol str)]
      (if-let [[_ name+ name-] (re-matches #"(.*)_(.*)" name)]
        ;Add spacing beside the shorter of the two names to better center them.
        (let [c+ (count name+)
              c- (count name-)
              s+ (-> (max (- c- c+) 0) (repeat " ") (->> (apply str)))
              s- (-> (max (- c+ c-) 0) (repeat " ") (->> (apply str)))]
          (str s- (capitalize name-) " --- " (capitalize name+) s+ suffix))
        (str (capitalize name) suffix)))))
      ; (str (capitalize name-) " ... " (str (capitalize name+)))
      ; (str (capitalize name)))))

(defn histogram-range
  "Takes a feature histogram and finds a range of feature values centered on the
   max value in the histogram. Returns
   {:range   [min-feature-value max-feature-value]
    :bins    number of bins covered by this range
    :total   sum of the values for all of these bins}

   Note that feature values for a direction or orientation wrap around, which can
   result in a min-feature-value that is larger than the max-feature-value.

   Keyword arguments are:
   :min-bins  range must include at least this many bins
   :max-bins  range must have at most this many bins
   :stop-at-inflections?  If this is true, then the range can't include any inflection
                          points in the histogram
   :target-total  If this value is specified, then find the minimal range whose
                  total is at least this amount. If this value isn't specified,
                  then the range will grow as large as possible, until we reach
                  max-bins, stop at inflections, or reach the end of the histogram."
  [histogram & {:keys [min-bins max-bins target-total stop-at-inflections?]}]
  (let [hist (:matrix histogram)
        ftype (:feature-type histogram)
        angle? (case ftype
                 (:direction :orientation) true
                 false)
        {best-amount :max-val best-loc :max-loc} (cv/min-max-loc hist)
        start (-> best-loc :y int)
        total-bins (cv/height hist)
        max-bins (or max-bins total-bins)
        max-val (if angle? 360 1.0)
        min-val (if (= ftype :difference) -1 0)
        val-range (- max-val min-val)
        poll-fn (if angle?
                  #(->> (mod % total-bins)
                        (cv/get-value hist 0))
                  #(when (and (>= % 0) (< % total-bins))
                     (cv/get-value hist 0 %)))
        check-fn (if stop-at-inflections?
                   (fn [new old] (when (and new (< new old)) new))
                   (fn [new old] new))]
    (when (not (Double/isInfinite best-amount))
      (loop [total best-amount
             bins 1
             pos-range 0
             neg-range 0
             next-pos (-> (poll-fn (inc start)) (check-fn total))
             next-neg (-> (poll-fn (dec start)) (check-fn total))]

        (cond
          ;;We're done
          (and (or (nil? min-bins) (>= bins min-bins))
               (or (and target-total (>= total target-total))
                   (and (nil? target-total)
                        (or (and (nil? next-pos) (nil? next-neg))
                            (= bins max-bins)))))
          {:total total :bins bins
           :range
           [(-> (- start neg-range) (mod total-bins) (/ (dec total-bins))
                (* val-range) (+ min-val))
            (-> (+ start pos-range) (mod total-bins) (/ (dec total-bins))
                (* val-range) (+ min-val))]}

          ;;We've run out of space before reaching min-bins or target-total
          (or (and (nil? next-pos) (nil? next-neg))
              (= bins max-bins))
          nil

          ;;Increase the range in the positive direction
          (and next-pos (or (nil? next-neg) (>= next-pos next-neg)))
          (recur (+ total next-pos) (inc bins)
                 (inc pos-range) neg-range
                 (-> (poll-fn (+ start (+ 2 pos-range))) (check-fn next-pos))
                 next-neg)

          ;;Increase the range in the negative direction
          :else
          (recur (+ total next-neg) (inc bins)
                 pos-range (inc neg-range)
                 next-pos
                 (-> (poll-fn (- start (+ 2 neg-range))) (check-fn next-neg))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns for producing feature maps and histograms.

(defn feature-map
  "Represent a feature map with the specified name and feature-type and,
   optionally, a corresponding :intensity map with name :nameIntensity. Other
   optional arguments:
     :nameX and :nameY, the name of the x- and y-component maps."
  ; ":min-intensity a minimum value for the intensity map (anything below will
  ;       be set to 0)"
  [src name feature-type & {:keys [intensity nameX nameY nameIntensity]}]
  (let [
        ; intensity (cond-> intensity
        ;                   (and intensity min-intensity)
        ;                   (cv/threshold min-intensity cv/THRESH_TOZERO))
        ; src (cond-> src
        ;             (and min-intensity (= feature-type :magnitude))
        ;             (cv/threshold min-intensity cv/THRESH_TOZERO)
        ;
        ;             (and min-intensity (= feature-type :difference))
        ;             (-> (cv/threshold min-intensity cv/THRESH_TOZERO)
        ;                 (cv/add! (cv/threshold src (- min-intensity) cv/THRESH_TOZERO_INV))))
        angle? (or (= feature-type :orientation) (= feature-type :direction))
        name (and name (-> name symbol str))
        nameX (and nameX (-> nameX symbol str))
        nameY (and nameY (-> nameY symbol str))
        [_ name+ name-] (when (and (not angle?) name)
                        (or (re-matches #"(.*)_(.*)" name)
                            [nil (str name "+") (str name "-")]))
        [nameX+ nameX-] (when (and angle? nameX)
                          (or (rest (re-matches #"(.*)_(.*)" nameX))
                              [(str nameX "+") (str nameX "-")]))
        [nameY+ nameY-] (when (and angle? nameY)
                          (or (rest (re-matches #"(.*)_(.*)" nameY))
                              [(str nameY "+") (str nameY "-")]))]
    (cond-> {:matrix src :feature-type feature-type :matrix-type :feature-map
             :name name}

            intensity (assoc :intensity intensity)
            angle?
            (assoc :components
                   (cv/polar-to-cart src (or intensity (cv/new-mat src :value 1))))

            name (assoc :name (keyword name))
            name+ (assoc :name+ (keyword name+))
            name- (assoc :name- (keyword name-))
            nameX (assoc :nameX (keyword nameX))
            nameX+ (assoc :nameX+ (keyword nameX+))
            nameX- (assoc :nameX- (keyword nameX-))
            nameY (assoc :nameY (keyword nameY))
            nameY+ (assoc :nameY+ (keyword nameY+))
            nameY- (assoc :nameY- (keyword nameY-))
            nameIntensity (assoc :nameIntensity (keyword nameIntensity)))))

(defn histogram
  "Computes a feature histogram, given a feature map. This histogram will be a
   1xbins matrix describing the percentage of total pixels that have a particular
   value across the full range of possible values.
   If the feature has an intensity feature map stored with it, then the histogram
   will instead describe
   (number of pixels * average intensity of those pixels) / (total pixels)
   for each value. Additionally, there will be a second, intensity histogram that will
   describe
   (number of pixels * average intensity of those pixels) / (total intensity of all pixels)

   If a mask is provided, the histogram is computed only over matrix elements covered
   by the mask. If a divisor is provided, it replaces the default of dividing by
   the total number of elements to normalize the histogram."
  [fmap & {:keys [bins mask divisor] :or {bins 41}}]
  (let [{src :matrix ftype :feature-type intensity :intensity} fmap
        divisor (or divisor (some-> mask cv/count-non-zero) (cv/area src))
        rng (case ftype
              :magnitude [0 1.01]
              :difference [-1 1.01]
              (:direction :orientation) [0 361])
        hist (cv/calc-hist src bins rng :mask mask)]
    (if intensity
      (let [hist-2d (cv/calc-hist-2d src bins rng intensity 20 [0 1.01] :mask mask)
            avg-intensity (cv/divide
                           (cv/* hist-2d intensity-wts)
                           (-> (cv/reduce hist-2d 1 cv/REDUCE_SUM) (cv/max 1.0)))
            ihist (cv/multiply hist avg-intensity)]
        {:matrix (cv/divide ihist divisor) :feature-type ftype :name (:name fmap)
         :matrix-type :feature-histogram
         :intensity (cv/divide ihist (max 0.01 (cv/sum-elems ihist)))})
      {:matrix (cv/divide hist divisor) :feature-type ftype :name (:name fmap)
       :matrix-type :feature-histogram})))

(defn magnify
  "Magnify a feature map's values, or if it is an angle map, its intensity and
   component values, by the specified multiplier."
  [{matrix :matrix ftype :feature-type old-multi :magnification :as fmap} multi]
  (let [final-multi (cond-> multi old-multi (* old-multi))]
    (cond
      (= ftype :magnitude)
      (assoc fmap :magnification final-multi
             :matrix (-> matrix (cv/multiply multi) (cv/min! 1.0)))

      (= ftype :difference)
      (assoc fmap :magnification final-multi
             :matrix (-> matrix (cv/multiply multi) (cv/min! 1.0) (cv/max! -1.0)))

      (or (= ftype :direction) (= ftype :orientation))
      (-> fmap
          (assoc :magnification final-multi)
          (update :intensity #(some-> % (cv/multiply multi) (cv/min! 1.0)))
          (update :components (fn [comps] (mapv #(-> % (cv/multiply multi)
                                                     (cv/min! 1.0) (cv/max! -1.0))
                                                comps)))))))

(defn intensity
  "Returns the intensity map for a feature map. The result will itself be encoded
   as a feature map, with the assumption that the intensity map caps out at 1."
  [{matrix :matrix ftype :feature-type intensity :intensity
    nameIntensity :nameIntensity :as fmap}]
  (if intensity
    {:matrix intensity :feature-type :magnitude :matrix-type :feature-map
     :name (or nameIntensity :intensity)}
    (throw (Exception. "Insufficient information to return intensity."))))

(defn x-component
  "Returns the first component for a :direction or :orientation feature map. The
   result will itself be encoded as a feature map, with the assumption that it
   ranges from -1 to 1."
  [{[x-comp] :components :as fmap}]
  (if x-comp
    {:matrix x-comp :feature-type :difference :matrix-type :feature-map
     :name (:nameX fmap) :name+ (:nameX+ fmap) :name- (:nameX- fmap)}
    (throw (Exception. "Insufficient information to return x-component."))))

(defn y-component
  "Returns the second component for a :direction or :orientation feature map. The
   result will itself be encoded as a feature map, with the assumption that it
   ranges from -1 to 1."
  [{[_ y-comp] :components :as fmap}]
  (if y-comp
    {:matrix y-comp :feature-type :difference :matrix-type :feature-map
     :name (:nameY fmap) :name+ (:nameY+ fmap) :name- (:nameY- fmap)}
    (throw (Exception. "Insufficient information to return y-component."))))

(defn x-component+
  "Returns the positive values only from the first component for a :direction
   or :orientation feature map. The result will itself be encoded as a feature map,
   with the assumption that it ranges from 0 to 1."
  [{[x-comp] :components :as fmap}]
  (if x-comp
    {:matrix (cv/max x-comp 0) :feature-type :magnitude :matrix-type :feature-map
     :name (:nameX+ fmap)}
    (throw (Exception. "Insufficient information to return x-component."))))

(defn x-component-
  "Returns the negative values only (changed to positive) from the first component
   for a :direction or :orientation feature map. The result will itself be encoded
   as a feature map, with the assumption that it ranges from 0 to 1."
  [{[x-comp] :components :as fmap}]
  (if x-comp
    {:matrix (-> x-comp (cv/multiply -1) (cv/max 0)) :feature-type :magnitude
     :matrix-type :feature-map :name (:nameX- fmap)}
    (throw (Exception. "Insufficient information to return x-component."))))

(defn y-component+
  "Returns the positive values only from the second component for a :direction
   or :orientation feature map. The result will itself be encoded as a feature map,
   with the assumption that it ranges from 0 to 1."
  [{[_ y-comp] :components :as fmap}]
  (if y-comp
    {:matrix (cv/max y-comp 0) :feature-type :magnitude :matrix-type :feature-map
     :name (:nameY+ fmap)}
    (throw (Exception. "Insufficient information to return y-component."))))

(defn y-component-
  "Returns the negative values only (changed to positive) from the second component
   for a :direction or :orientation feature map. The result will itself be encoded
   as a feature map, with the assumption that it ranges from 0 to 1."
  [{[_ y-comp] :components :as fmap}]
  (if y-comp
    {:matrix (-> y-comp (cv/multiply -1) (cv/max 0)) :feature-type :magnitude
     :matrix-type :feature-map :name (:nameY- fmap)}
    (throw (Exception. "Insufficient information to return x-component."))))

(defn positive
  "Returns the positive portion of a feature map."
  [{name :name+ feature-type :feature-type :as fmap}]
  (case feature-type
    :magnitude (-> fmap (assoc :name name) (dissoc :name+ :name-))
    :difference (-> fmap (assoc :name name) (dissoc :name+ :name-)
                    (update :matrix (cv/max 0)))
    (throw (Exception.
            "Unable to take the positive map of a feature-map of
             type :orientation or :direction."))))

(defn negative
  "Returns the negative portion of a :difference feature map, multiplied by -1 to
   make it positive. Or returns the complement of a :magnitude feature map."
  [{name :name- feature-type :feature-type :as fmap}]
  (case feature-type
    :magnitude (-> fmap (assoc :name name) (dissoc :name+ :name-)
                   (update :matrix #(-> % (cv/multiply -1) (cv/add 1))))
    :difference (-> fmap (assoc :name name) (dissoc :name+ :name-)
                    (update :matrix #(-> % (cv/multiply -1) (cv/max 0))))
    (throw (Exception.
            "Unable to take the negative map of a feature-map of
             type :orientation or :direction."))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns providing access to information in feature maps

(defn has-components?
  "Returns true if this feature map has x- and y-components."
  [fmap]
  (if (seq (:components fmap))
    true
    false))

(defn matrix
  "Returns the original matrix, if this is a feature map. Otherwise, returns
   this."
  [fmap]
  (or (:matrix fmap) fmap))

(defn name
  "Returns the feature map's name."
  [fmap]
  (:name fmap))

(defn subfeature-maps
  "Provides a hash-map of (:name :map) pairs for each subfeature of a feature map.
   If a map has components, these are the x-component+, x-component-, y-component+,
   and y-component-. Otherwise, there are the positive map and the negative map."
  [fmap]
  (g/seq-keyfun-valfun->map
   (if (seq (:components fmap))
     [(x-component+ fmap) (x-component- fmap)
      (y-component+ fmap) (y-component- fmap)]
     [(positive fmap) (negative fmap)])
   #(:name %)
   identity))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Fns replicating opencv functionality for feature maps

(defn height
  "Returns the height of a feature map."
  [fmap]
  (cv/height (:matrix fmap)))

(defn max-value
  "Returns the max value of a feature map."
  [{matrix :matrix ftype :feature-type :as fmap} & {:keys [mask]}]
  (if (or (= ftype :direction) (= ftype :orientation))
    (throw (Exception. "Unable to take max value of a direction or orientation
           feature map."))
    (cv/max-value matrix :mask mask)))

(defn mean-intensity
  "Returns the mean intensity of a feature map."
  [{matrix :matrix ftype :feature-type intensity :intensity :as fmap}
   & {:keys [mask]}]
  (cond
    intensity
    (cv/mean-value intensity :mask mask)

    (= ftype :magnitude)
    (cv/mean-value matrix :mask mask)

    (= ftype :difference)
    (-> (cv/abs matrix) (cv/mean-value :mask mask))

    :else
    (throw (Exception. "Insufficient information to compute mean intensity."))))

(defn mean-value
  "Returns the mean value of a feature map."
  [{matrix :matrix ftype :feature-type intensity :intensity :as fmap}
   & {:keys [mask]}]
  (cond
    (or (= ftype :direction) (= ftype :orientation))
    (let [[x-comp y-comp]
          (or (:components fmap)
              (and intensity (cv/polar-to-cart matrix intensity))
              (throw (Exception. "Unable to take mean value of a direction or orientation
                     feature map without knowing the intensity or components.")))]
      (-> (Math/atan2 (cv/mean-value y-comp :mask mask)
                      (cv/mean-value x-comp :mask mask))
          (* pi->deg) (mod 360)))

    intensity
    (-> matrix (cv/multiply intensity) (cv/mean-value :mask mask)
        (/ (cv/sum-elems intensity)))

    :else
    (cv/mean-value matrix :mask mask)))

(defn min-value
  "Returns the min value of a feature map."
  [{matrix :matrix ftype :feature-type :as fmap} & {:keys [mask]}]
  (if (or (= ftype :direction) (= ftype :orientation))
    (throw (Exception. "Unable to take min value of a direction or orientation
           feature map."))
    (cv/min-value matrix :mask mask)))

(defn score
  "Computes a feature score for a feature map, optionally applying a mask to it.
   The equation for the score is
   mean-value * sqrt(area)
   where the area is the overall size of the matrix, or the size of the mask if
   one is used. area-sqrt can be passed as a keyword argument to save time on
   computing it. If min-mean is provided, then the mean must be at least this large,
   or the score returned will be 0. If min-value is provided, then any individual
   values lower than this threshold will be set to 0 before computing the score."
  [{matrix :matrix ftype :feature-type intensity :intensity :as fmap}
   & {:keys [mask area-sqrt min-mean min-value]}]
  (let [multi (or area-sqrt (and mask (Math/sqrt (cv/count-non-zero mask)))
                  (Math/sqrt (cv/area matrix)))
        mean (-> matrix
                 (cond-> min-value (cv/threshold min-value cv/THRESH_TOZERO))
                 (cv/mean-value :mask mask))]
    (if (and min-mean (< mean min-mean))
      0.0
      (* multi mean))))

(defn submat
  "Returns a submatrix of a feature map, via a call to opencv/submat."
  [f & args]
  (cond-> (update f :matrix #(apply cv/submat % args))
          (:intensity f) (update :intensity #(apply cv/submat % args))
          (:components f)
          (update :components (fn [comps] (mapv #(apply cv/submat % args) comps)))))

(defn width
  "Returns the width of a feature map."
  [fmap]
  (cv/width (:matrix fmap)))

; (let [value (-> results :maps :peripheral :feature :color :value)
;       intensity (-> results :maps :peripheral :feature :color :intensity)
;       wts (->> (range 20) (map #(+ (/ % 20) (/ 1 40))) float-array cv/->java)
;       vi-hist (cv/calc-hist-2d value 30 [0 361] intensity 20 [0 1.01])
;       vi-wt-sum (cv/* vi-hist wts)
;       vi-sum (cv/reduce vi-hist 1 cv/REDUCE_SUM)
;       vi-sum-safe (cv/max vi-sum 1.0)
;       vi-intensity (cv/divide vi-wt-sum vi-sum-safe)]
;   (display-env)
;   (break))
