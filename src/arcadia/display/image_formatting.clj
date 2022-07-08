(ns
  ^{:doc "Supports formatting images for debug display components."}
  arcadia.display.image-formatting
  (:require [arcadia.utility [general :as g] [image :as img] [opencv :as cv]]
            [arcadia.display.support :as support]
            [arcadia.sensor.stable-viewpoint :as sensor]
            [arcadia.vision [features :as f] [segments :as seg]])
  (:import javax.swing.JLabel
           java.awt.image.BufferedImage
           java.awt.font.TextAttribute
           [java.awt AlphaComposite BasicStroke Color Font Polygon
            RenderingHints]))

(defn- blank-canvas
  "Create an image of the specified size with the specified color, encoded as BGR."
  [color width height]
  (when (and width height)
    (let [canvas (BufferedImage. width height BufferedImage/TYPE_3BYTE_BGR)
          g (.createGraphics canvas)]
      (.setColor g color)
      (.fillRect g 0 0 width height)
      (.dispose g)
      canvas)))

(defn- invisible-canvas
  "Create an invisible rectangle with the specified dimensions."
  [width height]
  (when (and width height)
    (let [canvas (BufferedImage. width height BufferedImage/TYPE_4BYTE_ABGR)
          g (.createGraphics canvas)]
      (.setComposite g (AlphaComposite/getInstance AlphaComposite/CLEAR 0.0))
      (.fillRect g 0 0 width height)
      (.dispose g)
      canvas)))

(defn- resolve-sensor-dimensions
  "Given debug-data (which records ARCADIA's current state) and the parameters,
   returns the dimensions of the input sensor as [width height]."
  [debug-data params]
  (or (some-> params :sensor
              (#(vector (sensor/camera-width %) (sensor/camera-height %)))
              (#(when (every? pos? %) %)))
      (some-> params :sensor-hash vals first
              (#(vector (sensor/camera-width %) (sensor/camera-height %)))
              (#(when (every? pos? %) %)))
      (some-> debug-data support/debug-data->state
              :image (#(vector (cv/width %) (cv/height %))))
      (some-> debug-data support/debug-data->state
              :image-hash vals first
            (#(vector (cv/width %) (cv/height %))))))

(defn resolve-image-dimensions
  "Given debug-data (optionally), which records ARCADIA's current state, and the
   parameters, returns the desired dimensions for images as [width height]."
  ([params]
   (resolve-image-dimensions nil params))
  ([debug-data params]
   (or (g/when-let* [width (:image-width params)
                     height (:image-height params)]
                    (vector width height))
       (g/when-let* [dims (resolve-sensor-dimensions debug-data params)
                     scale (:image-scale params)]
                    (mapv #(int (* % scale)) dims))
       (g/when-let* [[input-width input-height]
                     (resolve-sensor-dimensions debug-data params)
                     width (:image-width params)
                     scale (and width (/ width input-width))]
                    (vector width (int (* input-height scale))))
       (g/when-let* [[input-width input-height]
                     (resolve-sensor-dimensions debug-data params)
                     height (:image-height params)
                     scale (and height (/ height input-height))]
                    (vector (int (* input-width scale)) height)))))

(defn- resolve-scale
  "Given a canvas and an original element, returns the [scale-x scale-y] scale of
   the canvas, relative to the original element (NOTE: if the original element
   was a color, we'll just assume it was scaled relative to the sensor input).
   Or, alternatively, given an already computed scale and the parameters, returns
   either that scale or [1 1] if :scale-glyphs? is false."
  ([scale params]
   (if (:scale-glyphs? params)
     scale
     [1 1]))
  ([canvas original-element debug-data params]
   (cond
     (= (type original-element) Color)
     (if-let [[x y] (resolve-sensor-dimensions debug-data params)]
       [(/ (.getWidth canvas) x) (/ (.getHeight canvas) y)]
       [1 1])

     (= (cv/mat-type original-element) :java-mat)
     [(/ (.getWidth canvas) (cv/width original-element))
      (/ (.getHeight canvas) (cv/height original-element))]

     (= (type original-element) BufferedImage)
     [(/ (.getWidth canvas) (.getWidth original-element))
      (/ (.getHeight canvas) (.getHeight original-element))])))

(defmulti draw-glyph! "Draws a glyph onto the canvas."
  (fn [glyph canvas scale params] (or (cv/mat-type glyph) (type glyph)))
  :hierarchy (-> (make-hierarchy)
                 (derive java.lang.String :Text)
                 ; (derive clojure.core$keyword :Text)
                 (derive clojure.lang.Keyword :Text)
                 (derive clojure.lang.Symbol :Text)
                 (derive java.lang.Double :Text)
                 (derive java.lang.Long :Text)
                 (derive java.awt.Rectangle :Rect)
                 (derive org.opencv.core.Rect :Rect)
                 (derive clojure.lang.PersistentArrayMap :Map)
                 (derive clojure.lang.PersistentHashMap :Map)
                 (atom)))

(defn- draw-rectangle!
  "Draws a rectangle with the specified x, y, width, and height onto the canvas.
   Helper function for draw-glyph!"
  [x y width height canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        shape-scale (:shape-scale params)
        shape (:shape params)
        min-x (* scale-x
                 (+ x (* (- 1 shape-scale) width 0.5)
                    (:x-offset params)))
        min-y (* scale-y
                 (+ y (* (- 1 shape-scale) height 0.5)
                    (:y-offset params)))
        width (Math/round (* width scale-x shape-scale))
        height (Math/round (* height scale-y shape-scale))
        center-x (+ min-x (* (dec width) 0.5))
        center-y (+ min-y (* (dec height) 0.5))]
    (.setStroke g (BasicStroke. (:line-width params)))
    (.setComposite
     g (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))

    (when (= shape :oval)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (when (:fill-color params)
        (.setColor g (:fill-color params))
        (.fillOval g min-x min-y width height))
      (when (:color params)
        (.setColor g (:color params))
        (.drawOval g min-x min-y (dec width) (dec height))))

    (when (or (nil? shape) (= shape :rectangle))
      (when (:fill-color params)
        (.setColor g (:fill-color params))
        (.fillRect g min-x min-y width height))
      (when (:color params)
        (.setColor g (:color params))
        (.drawRect g min-x min-y (dec width) (dec height))))
    (when (= shape :x)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor g (:color params))
      (.drawLine g min-x min-y (+ min-x width) (+ min-y height))
      (.drawLine g min-x (+ min-y height) (+ min-x width) min-y))
    (when (= shape :cross)
      (.setColor g (:color params))
      (.drawLine g center-x min-y center-x (+ min-y height))
      (.drawLine g min-x center-y (+ min-x width) center-y))
    (.dispose g)))

(defmethod draw-glyph! java.awt.Polygon ;;<------Needs to be updated for proper use of :image-scale
  [poly canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        scaler #(* % (:scale params))
        poly (Polygon.
              (int-array (map #(* % scale-x) (.xpoints poly)))
              (int-array (map #(* % scale-y) (.ypoints poly)))
              (.npoints poly))]
    (.setStroke
     g (BasicStroke. (:line-width params)))
    (.setComposite
     g (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))

    (when (:fill-color params)
      (.setColor g (:fill-color params))
      (.fillPolygon g poly))

    (when (:color params)
      (.setColor g (:color params))
      (.drawPolygon g poly))
    (.dispose g)))

(defmethod draw-glyph! clojure.lang.PersistentVector
  [glyph canvas scale params]
  (cond
    (and (= (count glyph) 2) (every? number? glyph)) ;;It's an [x y] point.
    (draw-glyph! {:x (first glyph) :y (second glyph)} canvas scale params)

    ;;It's  a vector of [x y] points, treat them like a polyline.
    (every? support/get-point glyph)
    (let [g (.createGraphics canvas)
          [scale-x scale-y] (resolve-scale scale params)
          pts (map support/get-point glyph)
          xs (map #(* scale-x (first %)) pts)
          ys (map #(* scale-y (second %)) pts)]
      (doto
       g
       (.setStroke (BasicStroke. (:line-width params)))
       (.setComposite
        (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
       (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                          RenderingHints/VALUE_ANTIALIAS_ON)
       (.setColor (:color params)))

      (.drawPolyline g (int-array xs) (int-array ys) (count xs)))

    :else ;;Some other vector
    (draw-glyph! (str glyph) canvas scale params)))

;;A hashmap might be {:x x :y y}, a point. Or it might be {:x x :y y :width width
;;:height height} a rectangle. Or simply {:x x} or {:y y} a line. Or it might be
;;something else.
(defmethod draw-glyph! :Map
  [{x :x y :y width :width height :height region :region mask :mask image :image
    :as hash} canvas scale params]
  (cond
    ;;Segment
    (and region image)
    (draw-glyph! (img/mat-to-bufferedimage image mask) canvas scale
                 (assoc params :glyph-region region))

    (and region mask)
    (draw-glyph! (img/mat-to-bufferedimage mask)
                 canvas scale (assoc params :glyph-region region))

    region
    (draw-glyph! (seg/base-region hash) canvas scale params)

    ;;Point
    (and x y (nil? width) (nil? height))
    (let [w (or (some-> params :glyph-region :width) (:glyph-width params))
          h (or (some-> params :glyph-region :height) (:glyph-height params))
          params (cond-> params
                         (nil? (:shape params)) (assoc :shape :cross))]
      (draw-rectangle! (- x (/ (dec w) 2)) (- y (/ (dec h) 2)) w h
                       canvas scale params))

    ;;Line
    (or (and x (nil? y) (nil? width))
        (and y (nil? x) (nil? height)))
    (let [g (.createGraphics canvas)
          [scale-x scale-y] (resolve-scale scale params)]
      (doto
       g
       (.setStroke (BasicStroke. (:line-width params)))
       (.setComposite
        (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
       (.setColor (:color params)))
      (if x
        (.drawLine g (int (* x scale-x)) 0 (int (* x scale-x)) (.getHeight canvas))
        (.drawLine g 0 (int (* y scale-y)) (.getWidth canvas) (int (* y scale-y)))))

    (or x y)
    (let [[scale-x scale-y] (mapv float (resolve-scale scale params))
          final-width (or width (and x (:glyph-width params))
                          (Math/round (/ (.getWidth canvas) scale-x)))
          final-height (or height (and y (:glyph-height params))
                           (Math/round (/ (.getHeight canvas) scale-y)))
          min-x (or (and width x) (and x (- x (/ (dec final-width) 2)))
                    0)
          min-y (or (and height y) (and y (- y (/ (dec final-height) 2)))
                    0)]
      (draw-rectangle! min-x min-y final-width final-height
                       canvas scale params))

    :else
    (draw-glyph! (str hash) canvas scale params)))

(defmethod draw-glyph! :Text
  [text canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        font (.getFont (JLabel.))
        ;;Use resolve to avoid circular dependencies
        text ((resolve 'arcadia.display.formatting/get-text) text params)
        ^java.util.Map text-attrs
        {TextAttribute/UNDERLINE (if (:underline? params)
                                   TextAttribute/UNDERLINE_ON 0)
         TextAttribute/STRIKETHROUGH (if (:strike-through? params)
                                       TextAttribute/STRIKETHROUGH_ON 0)}]
    (.setColor g (or (:color params) java.awt.Color/black))
    (.setFont g (. (Font. (or (:font-family params) (.getFamily font))
                          (bit-or (if (:italic? params) Font/ITALIC 0)
                                  (if (:bold? params) Font/BOLD 0))
                          (int (* (.getSize font) (or (:text-size params) 1))))
                   (deriveFont text-attrs)))

    (.setComposite
     g (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (.drawString g (str text)
                 (int (* scale-x
                         (+ (or (-> params :glyph-region :x) (:glyph-x params))
                            (:x-offset params))))
                 (int (* scale-y
                         (+ (or (-> params :glyph-region :y) (:glyph-y params))
                            (:y-offset params)))))
    (.dispose g)))

(defmethod draw-glyph! BufferedImage
  [image canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)]
    (.setComposite
     g (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
    (try
      (.drawImage g (img/scale-bufferedimage image scale-x)
                  (int (* scale-x
                          (+ (or (-> params :glyph-region :x) (:glyph-x params))
                             (:x-offset params))))
                  (int (* scale-y
                          (+ (or (-> params :glyph-region :y) (:glyph-y params))
                             (:y-offset params))))
                  nil)
      ;; NOTE: we need a logging capability so that we can report exceptions and other
      ;; happenings.
      ;; NOTE: in this case, scaling can potentially lead to a glyph with 0 width or
      ;; height. this causes an exception. in this case, we'll just skip drawing the
      ;; glyph and continue onward.
      (catch Exception e ))
    (.dispose g)))

(defmethod draw-glyph! :java-mat
  [mat canvas scale params]
  (draw-glyph!
   (cond-> mat
           (:image-format-fn params) ((:image-format-fn params))
           true (img/mat-to-bufferedimage (:glyph-mask params)))
   canvas scale params))

(defmethod draw-glyph! nil
  [glyph canvas scale params])
(defmethod draw-glyph! :default
  [glyph canvas scale params]
  (println "DON'T KNOW HOW TO DRAW A GLYPH OF TYPE" (type glyph)))

(defn- draw-glyphs!
  "Draws the list of glyphs onto the canvas."
  [canvas glyphs original-element debug-data params]
  (let [scale (resolve-scale canvas original-element debug-data params)]
    (doseq [{glyph :glyph params :params} glyphs]
      (draw-glyph! glyph canvas scale params))
    canvas))

(defn resolve-image
  "Given some element, the debug-data object that holds ARCADIA's state, and
   the parameters, generate an image from the element. If base-glyphs is
   provided, it should be a list of items of the form
   [glyph param-value param-key, param-vaue param-key, ...]. If base-glyphs is
   not provided, then the glyphs in params, if any, will be used."
  ([element debug-data params]
   (resolve-image element debug-data params nil))
  ([element debug-data params base-glyphs]
   (let [[width height] (resolve-image-dimensions debug-data params)
         glyphs (or (and base-glyphs
                         (map #(-> (apply support/initialize-glyph (cons false %))
                                   (support/merge-params-into-info params))
                              base-glyphs))
                    (:glyphs params))]
     (cond
       (and (= (type element) Color) width)
       (draw-glyphs! (blank-canvas element width height)
                     glyphs element debug-data params)

       (and (= (cv/mat-type element) :java-mat)
            (not (= (cv/channels element) 2)))
       (cond-> element
               width (img/resize-image width height true (:interpolation-method params))
               (:image-format-fn params) ((:image-format-fn params))
               true
               (img/mat-to-bufferedimage
                (:glyph-mask params)
                (or width (not (:copy-image? params))))
               true (draw-glyphs! glyphs element debug-data params))

       (= (type element) BufferedImage)
       (cond-> element
               (and (nil? width) (:copy-image? params))
               img/bufferedimage-copy
               width (img/resize-bufferedimage width height true)
               true (draw-glyphs! glyphs element debug-data params))

       (f/visualizable? element)
       (resolve-image (f/visualize element) debug-data params base-glyphs)

       (cv/mat-type element)
       (resolve-image (cv/->java element) debug-data params base-glyphs)

       ;;If we need to make an image, just make an invisible image.
       (and (= (:element-type params) :image) width)
       (invisible-canvas width height)))))
