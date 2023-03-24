(ns
  ^{:doc "Supports formatting images for debug display components."}
  arcadia.display.image-formatting
  (:require [arcadia.utility.colors :refer [->java]]
            [arcadia.utility.general :as g]
            [arcadia.utility.geometry :as geo]
            [arcadia.utility.image :as img]
            [arcadia.utility.opencv :as cv]
            [arcadia.display.support :as support]
            [arcadia.sensor.stable-viewpoint :as sensor]
            [arcadia.vision.features :as f]
            [arcadia.vision.segments :as seg])
  (:import javax.swing.JLabel
           java.awt.image.BufferedImage
           java.awt.font.TextAttribute
           [java.awt AlphaComposite BasicStroke Font Polygon
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
   returns the dimensions of the input sensor as {:width width :height height}."
  [debug-data params]
  (or (some-> params :sensor 
              (#(hash-map :width (sensor/camera-width %) :height (sensor/camera-height %)))
              (#(when (every? pos? (vals %)) %)))
      (some-> params :sensor-hash vals first
              (#(hash-map :width (sensor/camera-width %) :height (sensor/camera-height %)))
              (#(when (every? pos? (vals %)) %)))
      (some-> debug-data support/debug-data->state :image cv/size)
      (some-> debug-data support/debug-data->state :image-hash vals first cv/size)))

(defn- get-color
  "Takes the map of parameters, gets the :color parameter value (e.g., :red), and returns the corresponding 
   java object. Returns black if the :color parameter is nil."
  [params]
  (or (some-> (:color params) ->java) (->java :black)))

(defn- get-colors 
  "Takes the map of parameters, gets the :color and :fill-color parameters (e.g., :red), and
   returns the corresponding [color fill-color] java objects. If no parameters are set, returns
   a black color and no fill-color."
  [params]
  [(or (some-> (:color params)->java) (when (nil? (:fill-color params)) (->java :black)))
   (some-> (:fill-color params) ->java)])

(defn resolve-image-dimensions
  "Given debug-data (optionally), which records ARCADIA's current state, and the
   parameters, returns the desired dimensions for images as {:width width :height height}."
  ([params]
   (resolve-image-dimensions nil params))
  ([debug-data params]
   (or (g/when-let* [width (:image-width params)
                     height (:image-height params)]
                    {:width width :height height})
       (g/when-let* [dims (resolve-sensor-dimensions debug-data params)
                     scale (:image-scale params)]
                    (g/update-all dims #(int (* % scale))))
       (g/when-let* [{input-width :width input-height :height} (resolve-sensor-dimensions debug-data params)
                     width (:image-width params)
                     scale (and width (/ width input-width))]
                    {:width width :height (int (* input-height scale))})
       (g/when-let* [{input-width :width input-height :height} (resolve-sensor-dimensions debug-data params)
                     height (:image-height params)
                     scale (and height (/ height input-height))]
                    {:width (int (* input-width scale)) :height height}))))

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
     (-> original-element meta :blank-canvas?)
     (if-let [{w :width h :height} (resolve-sensor-dimensions debug-data params)]
       [(/ (.getWidth canvas) w) (/ (.getHeight canvas) h)]
       [1 1])

     (= (cv/mat-type original-element) :java-mat)
     [(/ (.getWidth canvas) (cv/width original-element))
      (/ (.getHeight canvas) (cv/height original-element))]

     (= (type original-element) BufferedImage)
     [(/ (.getWidth canvas) (.getWidth original-element))
      (/ (.getHeight canvas) (.getHeight original-element))])))

(defmulti draw-glyph! "Draws a glyph onto the canvas."
  (fn [glyph canvas scale params] (or (and (:region glyph) :segment) (geo/type glyph)
                                      (cv/mat-type glyph) (type glyph)))
  :hierarchy (-> @geo/type-hierarchy
                 (derive java.lang.String :Text)
                 ; (derive clojure.core$keyword :Text)
                 (derive clojure.lang.Keyword :Text)
                 (derive clojure.lang.Symbol :Text)
                 (derive java.lang.Double :Text)
                 (derive java.lang.Long :Text)
                 (atom)))

(defmethod draw-glyph! :segment
  [{region :region mask :mask image :image :as hash} canvas scale params]
  (cond
    (and region image)
    (draw-glyph! (img/mat-to-bufferedimage image mask) canvas scale
                 (assoc params :glyph-region region))

    (and region mask)
    (draw-glyph! (img/mat-to-bufferedimage mask)
                 canvas scale (assoc params :glyph-region region))

    :else
    (draw-glyph! (seg/base-region hash) canvas scale params)))

(defmethod draw-glyph! :line
  [{x :x y :y} canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)]
    (doto
     g
      (.setStroke (BasicStroke. (:line-width params)))
      (.setComposite
       (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
      (.setColor (get-color params)))
    (if x
      (.drawLine g (int (* x scale-x)) 0 (int (* x scale-x)) (.getHeight canvas))
      (.drawLine g 0 (int (* y scale-y)) (.getWidth canvas) (int (* y scale-y))))))

(defmethod draw-glyph! :line-segment
  [lineseg canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        {x :x y :y width :width height :height}
        (-> lineseg (geo/scale-world scale-x scale-y) (geo/scale (:shape-scale params)))]
    (doto
     g
      (.setStroke (BasicStroke. (:line-width params)))
      (.setComposite
       (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
      (.setColor (get-color params)))
    (if width
      (.drawLine g x y (+ x (dec width)) y)
      (.drawLine g x y x (+ y (dec height))))))

;;For points, the default shape should be :cross
(defmethod draw-glyph! :point
  [pt canvas scale params]
  ((get-method draw-glyph! :axis-aligned)
   pt canvas scale (cond-> params
                     (nil? (:shape params)) (assoc :shape :cross))))

(defmethod draw-glyph! :axis-aligned ;;Any axis-aligned geometric shape not otherwise handled 
  [{x0 :x y0 :y w0 :width h0 :height :as region} canvas scale params]
  (let [[scale-x scale-y] (mapv float (resolve-scale scale params))

        ;;If we don't have all four values necessary for a rectangle (x0, y0, w0, h0), then 
        ;;compute any missing values.
        w1 (or w0 (and x0 (some-> params :glyph-region :width))
               (and x0 (:glyph-width params))
               (Math/round (/ (.getWidth canvas) scale-x)))
        h1 (or h0 (and y0 (some-> params :glyph-region :height))
               (and y0 (:glyph-height params))
               (Math/round (/ (.getHeight canvas) scale-y)))
        x1 (or (and w0 x0) (and x0 (int (- x0 (/ (dec w1) 2))))
               0)
        y1 (or (and h0 y0) (and y0 (int (- y0 (/ (dec h1) 2))))
               0)

        ;;Now take our four values for a rectangle and scale them, to get the final four values
        {x2 :x y2 :y w2 :width h2 :height}
        (-> {:x x1 :y y1 :width w1 :height h1}
            (geo/scale-world scale-x scale-y) (geo/scale (:shape-scale params)))

        shape (:shape params)
        [color fill-color] (get-colors params)
        g (.createGraphics canvas)]
    
    (.setStroke g (BasicStroke. (:line-width params)))
    (.setComposite
     g (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))

    (when (= shape :oval)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (when fill-color
        (.setColor g fill-color)
        (.fillOval g x2 y2 w2 h2))
      (when color
        (.setColor g color)
        (.drawOval g x2 y2 (dec w2) (dec h2))))

    (when (or (nil? shape) (= shape :rectangle))
      (when fill-color
        (.setColor g fill-color)
        (.fillRect g x2 y2 w2 h2))
      (when color
        (.setColor g color)
        (.drawRect g x2 y2 (dec w2) (dec h2))))
    
    (when (= shape :x)
      (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor g color)
      (.drawLine g x2 y2 (+ x2 w2) (+ y2 h2))
      (.drawLine g x2 (+ y2 h2) (+ x2 w2) y2))
    
    (when (= shape :cross)
      (let [center-x (+ x2 (* (dec w2) 0.5))
            center-y (+ y2 (* (dec h2) 0.5))]
        (.setColor g color)
        (.drawLine g center-x y2 center-x (+ y2 h2))
        (.drawLine g x2 center-y (+ x2 w2) center-y)))
    (.dispose g)))

(defmethod draw-glyph! :polyline
  [poly canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        pts (-> poly (geo/scale-world scale-x scale-y) (geo/scale (:shape-scale params)) :points)]
    (doto
     g
      (.setStroke (BasicStroke. (:line-width params)))
      (.setComposite
       (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params)))
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                         RenderingHints/VALUE_ANTIALIAS_ON)
      (.setColor (get-color params)))

    (.drawPolyline g (int-array (map :x pts)) (int-array (map :y pts)) (count pts))))

(defmethod draw-glyph! :polygon
  [poly canvas scale params]
  (let [g (.createGraphics canvas)
        [scale-x scale-y] (resolve-scale scale params)
        pts (-> poly (geo/scale-world scale-x scale-y) (geo/scale (:shape-scale params)) :points)
        poly (Polygon. (int-array (map :x pts)) (int-array (map :y pts)) (count pts))
        [color fill-color] (get-colors params)]
    (doto
     g
      (.setStroke (BasicStroke. (:line-width params)))
      (.setComposite
       (AlphaComposite/getInstance AlphaComposite/SRC_OVER (:alpha params))))

    (when fill-color
      (.setColor g fill-color)
      (.fillPolygon g poly))

    (when color
      (.setColor g color)
      (.drawPolygon g poly))
    (.dispose g)))

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
    (.setColor g (get-color params))
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
      (catch Exception e))
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
   (let [{width :width height :height} (resolve-image-dimensions debug-data params)
         glyphs (or (and base-glyphs
                         (map #(-> (apply support/initialize-glyph (cons false %))
                                   (support/merge-params-into-info params))
                              base-glyphs))
                    (:glyphs params))]
     (cond
       (and (-> element meta :blank-canvas?) width)
       (draw-glyphs! (blank-canvas (get-color element) width height)
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
