(ns
  ^{:doc "Functions for operating on images."}
  arcadia.utility.image
  (:import
   [javax.swing WindowConstants SwingUtilities]
   [java.awt.image BufferedImage])
  (:require [arcadia.utility.opencv :as cv]
            [clojure.math.numeric-tower :as math]))
;; These colors are defined using OpenCV's HSV color space.
;; Hue- 0 (0 degrees) to 180 (360 degrees)
;; Saturation- 0 (0%) to 255 (100%)
;; Value/Brightness- 0 (0%) to 255 (100%)
(def HSV-color-ranges
  {"blue" [{:low [100 80 0]
            :high [130 255 255]}]
   "red" [{:low [0 80 0]
           :high [10 255 255]}
          {:low [160 80 0]
           :high [180 255 255]}]
   "yellow" [{:low [20 100 100]
              :high [30 255 255]}]
   "green" [{:low [45 50 0]
             :high [70 255 255]}]
   "cyan" [{:low [70 10 0]
            :high [105 255 255]}]
   "purple" [{:low [125 10 0]
              :high [150 255 255]}]
   "brown" [{:low [10 10 0]
             :high [20 255 255]}]
   "black" [{:low [0 0 0]
             :high [180 255 25]}]
   "gray" [{:low [0 0 25]
            :high [180 20 255]}]
   "white" [{:low [0 0 200]
             :high [180 100 255]}]})

(defn bufferedimage-type
  "Takes an integer type value for a BufferedImage and translates it."
  [depth]
  (case depth
    0 :CUSTOM
    2 :INT_ARGB
    3 :INT_ARGB_PRE
    4 :INT_BGR
    5 :3BYTE_BGR
    6 :4BYTE_ABGR
    7 :4BYTE_ABGR_PRE
    8 :USHORT_565_RGB
    9 :USHORT_555_RGB
    10 :BYTE_GRAY
    11 :USHORT_GRAY
    12 :BYTE_BINARY
    13 :BYTE_INDEXED))

(defn abgr-to-bgr
  "Strips the alpha channel from an abgr matrix"
  [abgrmat]
  (cv/mix-channels [abgrmat] [(cv/new-mat abgrmat :channels 3)] [1 0 2 1 3 2]))

(defn bgr-to-rgba
  "Adds the alpha channel to a bgr matrix and orders the channels in preparation
   for conversion to a bufferedimage."
  [bgrmat amat]
  (cv/mix-channels [bgrmat amat] [(cv/new-mat bgrmat :channels 4)]
                   [2 0 1 1 0 2 3 3]))

(defn mat-to-bufferedimage
  "Convert an OpenCV matrix to a Java BufferedImage. Can optionally specify
   alpha-matrix, a separate matrix with alpha values. If destructive? is true,
   the src matrix can be modified during processing."
  ([src-matrix]
   (mat-to-bufferedimage src-matrix nil false))

  ([src-matrix alpha-matrix]
   (mat-to-bufferedimage src-matrix alpha-matrix false))

  ([src-matrix alpha-matrix destructive?]
   (let [matrix-image (cond-> src-matrix alpha-matrix (bgr-to-rgba alpha-matrix))
         w (cv/width matrix-image)
         h (cv/height matrix-image)
         ch (cv/channels matrix-image)
         eight-bit-matrix (if (or destructive? alpha-matrix)
                            matrix-image (cv/new-java-mat))
         bi (BufferedImage.
             w h
             (case ch
               1 BufferedImage/TYPE_BYTE_GRAY
               3 BufferedImage/TYPE_3BYTE_BGR
               4 BufferedImage/TYPE_4BYTE_ABGR))]
     (case ch
       1 (cv/convert-to matrix-image cv/CV_8UC1 :dst eight-bit-matrix)
       3 (cv/convert-to matrix-image cv/CV_8UC3 :dst eight-bit-matrix)
       4 (cv/convert-to matrix-image cv/CV_8UC4 :dst eight-bit-matrix))

     ;;This has to be done because the ordering of channels seems to be reversed
     ;;when we write to a BufferedImage.
     (when (= ch 3)
       (cv/cvt-color! eight-bit-matrix cv/COLOR_BGR2RGB))
     ;;If channels = 4, assume the ordering of channels was already corrected
     ;;with a call to bgr-to-rgba.

     (.setDataElements (.getRaster bi) 0 0 w h (cv/->array eight-bit-matrix))
     bi)))

(defn resize-bufferedimage
  "Resize a BufferedImage to the specified width and height."
  ([image width height]
   (let [new-image (BufferedImage. width height (.getType image))]
     (doto (.createGraphics new-image)
           (.drawImage image 0 0 width height nil)
           (.dispose))
     new-image))

  ([image width height proportional?]
   (if proportional?
     (let [ratio (min (/ width (.getWidth image)) (/ height (.getHeight image)))]
       (resize-bufferedimage image (int (* ratio (.getWidth image)))
                             (int (* ratio (.getHeight image)))))
     (resize-bufferedimage image width height))))

(defn scale-bufferedimage
  "Give a bufferedimage a new size that this the specified multiple of its
   original size."
  [image scale]
  (resize-bufferedimage image (int (* scale (.getWidth image)))
                        (int (* scale (.getHeight image)))))

(defn color-mask
  "Determines if the HSV OpenCV matrix falls within the specified range.

  The binary version of this function expects a sequence of color-ranges
  of the form {:low <LOWER-THRESHOLD> :high <UPPER-THRESHOLD>}."
  ([mat color-ranges]
   (case (count color-ranges)
     1 (color-mask mat (:low (first color-ranges)) (:high (first color-ranges)))
     2 (color-mask mat (:low (first color-ranges)) (:high (first color-ranges))
                   (:low (second color-ranges)) (:high (second color-ranges)))
     ;; default to zero matrix
     (cv/zeros mat :type cv/CV_8UC3)))
  ([mat low high]
   (cv/in-range mat low high))
  ([mat low1 high1 low2 high2]
   (cv/bitwise-or! (cv/in-range mat low1 high1) (cv/in-range mat low2 high2))))

(defn resize-image
  "
  Resize an OpenCV matrix to the specified width and height. If proportional? is true,
  preserves the proportions of the original image. If interpolation is provided,
  uses this opencv interpolation method (e.g., opencv/INTER_LINEAR). Possible inputs are:
  [mat width height]
  [mat width height propotional?]
  [mat width height proportional? interpolation]"
  ([mat width height]
   (resize-image mat width height false nil))
  ([mat width height proportional?]
   (resize-image mat width height proportional? nil))
  ([mat width height proportional? interpolation]
   (if proportional?
     (let [[mwidth mheight] (cv/size mat)
           ratio (min (/ width mwidth) (/ height mheight))]
       (cv/resize mat [(int (* ratio mwidth)) (int (* ratio mheight))]
                  :interpolation interpolation))
     (cv/resize mat [width height] :interpolation interpolation))))

;; This is an auxillary function for bufferedimage-to-mat.
(defn read-matrix
  "Convert a BufferedImage into an OpenCV matrix of the given type."
  [bi cvtype]
  (let [read-mat (cv/new-java-mat [(.getWidth bi) (.getHeight bi)] cvtype)]
    (cv/set-values read-mat 0 0 (.. bi getRaster getDataBuffer getData))
    read-mat))

;;;;
;; Given a BufferedImage, returns an OpenCV matrix of the requested
;; matrix type.
;;
;; useful types include
;; CV_8UC3: 8-bit unsigned char, 3 color channels
;;          range 1...255
;;          useful for display
;; CV_32FC3: 32-bit float, 3 color channels
;;           range 0.0...1.0
;;           useful for (Gabor) filtering
;;
;; The BufferedImage must have one of the following types.
;;   TYPE_BYTE_GRAY
;;   TYPE_INT_BGR
;;   TYPE_3BYTE_BGR
;;   TYPE_4BYTE_ABGR
(defn bufferedimage-to-mat
  "Converts a java.awt.image.BufferedImage into an OpenCV Mat of the given type (default: CV_32FC3)."
  ([buffered-image]
   (bufferedimage-to-mat buffered-image cv/CV_32FC3))
  ([buffered-image cvtype]
   ;; currently, only four buffered image types are supported
   (when-let [read-mat
              (condp = (.getType buffered-image)
                BufferedImage/TYPE_BYTE_GRAY (read-matrix buffered-image cv/CV_8UC1)
                BufferedImage/TYPE_INT_BGR   (read-matrix buffered-image cv/CV_8UC3)
                BufferedImage/TYPE_3BYTE_BGR (read-matrix buffered-image cv/CV_8UC3)
                BufferedImage/TYPE_4BYTE_ABGR
                (abgr-to-bgr (read-matrix buffered-image cv/CV_8UC4))
                (throw (UnsupportedOperationException.
                        (format "Invalid image type: %s." (.getType buffered-image)))))]
     (cv/convert-to read-mat cvtype))))


;; Ex/
;; (read-image "http://i0.kym-cdn.com/photos/images/original/000/406/282/2b8.jpg")
(defn read-image
  "Given a URL as a string, reads an image into a BufferedImage."
  [url-string]
  (javax.imageio.ImageIO/read
   (java.net.URL. url-string)))

(defn display-image
  "Display a BufferedImage in a frame with the given width and height (default: 600x800)."
  ([img]
   (display-image img 600 800 ""))
  ([img w h]
   (display-image img w h ""))
  ([img w h iname]
   (SwingUtilities/invokeAndWait
    (fn []
      (let [frame (javax.swing.JFrame. iname)
            lblimage (javax.swing.JLabel. (javax.swing.ImageIcon. img))]
        (.add (.getContentPane frame) lblimage java.awt.BorderLayout/CENTER)
        (.setDefaultCloseOperation frame WindowConstants/DISPOSE_ON_CLOSE)
        (.setSize frame w h)
        (.setVisible frame true))))))

(defn display-image!
  "Display a BufferedImage in the specified frame. (alters frame state)"
  [frame img]
  (SwingUtilities/invokeAndWait
   (fn []
     (let [lblimage (javax.swing.JLabel. (javax.swing.ImageIcon. img))]
       ;(.setVisible frame false)
       (.. frame getContentPane removeAll)
       (.add (.getContentPane frame) lblimage java.awt.BorderLayout/CENTER)
       ;; for demonstration purposes, ensure that the image frame isn't too
       ;; small to actually see.
       (.setSize (.getContentPane frame) (max 350 (.getWidth img)) (max 350 (.getHeight img)))
       (.pack frame)
       ;; only setVisible if not already visible, so the focus doesn't change
       (when (not (.isVisible frame))
         (.setVisible frame true))))))

(defn write-bufferedimage
  "Write a BufferedImage to the specified file as a JPEG, or optionally allow
   a different file type to be specified."
  ([bi file-name]
   (write-bufferedimage bi file-name "jpg"))
  ([bi file-name file-type]
   (javax.imageio.ImageIO/write bi file-type (java.io.File. file-name))))

(defn write-mat
  "Write an OpenCV matrix to the specified file as a JPEG."
  [mat file-name]
  (write-bufferedimage (mat-to-bufferedimage mat) file-name))

(defn load-bufferedimage
  "Read a BufferedImage from a specified file."
  [file-name]
  (javax.imageio.ImageIO/read (java.io.File. file-name)))

(defn extract-roi
  "Extracts a copy of an OpenCV region of interest from an OpenCV matrix. Regions
   should be hash-maps of the form {:x x :y y :width w :height h}."
  [image roi]
  (cv/copy (cv/submat image roi)))

(defn extract-rois
  "Extracts a copy of all the specified image regions in an OpenCV matrix. Regions
   should be hash-maps of the form {:x x :y y :width w :height h}."
  [image rois]
  (doall (map #(extract-roi image %) rois)))

(defn bufferedimage-copy
  "Return a deep copy of the given BufferedImage."
  [bi]
  (BufferedImage. (.getColorModel bi)
                  (.copyData bi nil)
                  (.isAlphaPremultiplied (.getColorModel bi))
                  nil))

;;;;
;; hot subimages of bufferedimages are bad. it's fine
;; and everything, but when you go to convert them to opencv
;; matrices in an uncomplicated fashion, you end up with garbage
;; because the arrays are read past the end of the subimage.
;; whoops. there might be a cleaner way than
;; (.. buffered-image getRaster getDataBuffer getData)
;; to get the raw data of a buffered image, but this'll help for
;; now.
(defn subimage-copy
  "Returns a deep copy of a subimage from a BufferedImage."
  [img xpos ypos width height]
  (let [si (.getSubimage img xpos ypos width height)
        wr (.createCompatibleWritableRaster (.getRaster si) width height)]
    (.copyData si wr)
    ;; if you only attempt to copy the buffered image from the
    ;; subimage, then you get padding from zeroed pixels up to
    ;; the size of the original image. that's bad.
    (BufferedImage. (.getColorModel img) wr
                    (.isAlphaPremultiplied (.getColorModel img))
                    nil)))



;; each of the channels is stored in a separate matrix.
(defn color-histogram
  "Returns separate histogram matrices of the HSV values of an OpenCV matrix."
  ([matrix]
   (color-histogram matrix 256))
  ([matrix bin-count]
   (let [[h s v] (-> (cv/cvt-color matrix cv/COLOR_BGR2HSV) cv/split)]
     {:hue (cv/calc-hist h bin-count [0 256])
      :saturation (cv/calc-hist s bin-count [0 256])
      :value (cv/calc-hist v bin-count [0 256])})))

;; NOTE: this is really a histogram of hue and saturation values only.
;; OpenCV's normalize function will not work on matrices with more
;; than 2 dimensions, because arbitrary.
(defn calculate-hsv-histogram
  "Returns a single matrix that contains hue and saturation values of an OpenCV matrix."
  ([matrix]
   (calculate-hsv-histogram matrix 32))
  ([matrix bin-count]
   (let [[h s v] (-> (cv/cvt-color matrix cv/COLOR_BGR2HSV) cv/split)]
     (-> (cv/calc-hist-2d h bin-count [0 180] s bin-count [0 256])
         (cv/normalize 0 1 cv/NORM_MINMAX)))))

;;;;
;; returns the degree of correlation between two histograms.
(defn compare-histograms
  ([h1 h2]
   (cv/compare-hist h1 h2 cv/COMP_CORREL))
  ([h1 h2 method-str]
   (cond
     (= method-str "intersection")
     (cv/compare-hist h1 h2 cv/COMP_INTERSECT)

     (or (= method-str "bhattacharyya") (= method-str "hellinger"))
     (cv/compare-hist h1 h2 cv/COMP_BHATTACHARYYA)

     (= method-str "chisqr")
     (cv/compare-hist h1 h2 cv/COMP_CHISQR)

     :else
     (compare-histograms h1 h2))))

(defn plot-color-channel
  "Draws one channel of a histogram onto an image."
  [channel image color]
  (when (and channel image)
    (let [bin-count (cv/height channel)
          img-height (cv/height image)
          norm-channel (cv/normalize channel 0 img-height cv/NORM_MINMAX)
          bin-width (math/round (double (/ (cv/width image) bin-count)))]
      (dotimes [idx (dec bin-count)]
        (cv/line image
                 {:x (* bin-width idx)
                  :y (- img-height (math/round (cv/get-value norm-channel 0 idx)))}
                 {:x (* bin-width (inc idx))
                  :y (- img-height (math/round (cv/get-value norm-channel 0 (inc idx))))}
                 color :thickness 2 :line-type 8))
      image)))

(defn plot-color-histogram
  "Plots the HSV histogram."
  [histogram]
  (let [image (cv/zeros [512 300] cv/CV_8UC3)]
    (plot-color-channel (:hue histogram) image [255 0 0])
    (plot-color-channel (:saturation histogram) image [0 255 0])
    (plot-color-channel (:value histogram) image [0 0 255])))


(defn- interpolate-colors
  "Assists in generating a color map. Takes three arrays of length 256 representing
   the B, G, and R channels of the output. Iterates through these arrays from
   y1 to y2 and fills in their values by interpolating from start to end."
  [B G R y1 y2 start end]
  (loop [i y1]
    (let [perc (float (/ (- i y1) (- y2 y1)))]
      (aset B i (Math/round (+ (aget start 0) (* (- (aget end 0) (aget start 0)) perc))))
      (aset G i (Math/round (+ (aget start 1) (* (- (aget end 1) (aget start 1)) perc))))
      (aset R i (Math/round (+ (aget start 2) (* (- (aget end 2) (aget start 2)) perc)))))

    (if (>= i y2)
      [B G R]
      (recur (inc i)))))

(defn make-colormap-RY_black_BG-smooth
  "Constructs a color map in which values from 255 to 0 map onto colors ranging from
  red to yellow to blue to green. Values in the middle of the range will map to a black
  band that will appear between yellow and blue."
  []
  (let [blue (int-array 3 [255 0 0])
        black (int-array 3 [0 0 0])
        red (int-array 3 [0 0 255])
        green (int-array 3 [0 255 0])
        yellow (int-array 3 [0 255 255])
        B (int-array 256)
        G (int-array 256)
        R (int-array 256)]
      (interpolate-colors B G R 0 20 green green)
      (interpolate-colors B G R 20 100 green blue)
      (interpolate-colors B G R 100 126 blue black)
      (interpolate-colors B G R 126 129 black black)
      (interpolate-colors B G R 129 145 black yellow)
      (interpolate-colors B G R 145 245 yellow red)
      (interpolate-colors B G R 245 255 red red)
      (cv/create-lookup-table B :G G :R R :table-type cv/CV_8U)))

(defn make-colormap-RY_black_BG
  "Constructs a color map in which values from 255 to 0 map onto colors ranging from
  red to yellow to blue to green. Values in the middle of the range will map to a black
  band that will appear between yellow and blue."
  []
  (let [blue (int-array [255 0 0])
        black (int-array [0 0 0])
        red (int-array [0 0 255])
        green (int-array [0 255 0])
        yellow (int-array [0 255 255])
        B (int-array 256)
        G (int-array 256)
        R (int-array 256)]
    (interpolate-colors B G R 0 125 green black)
    (interpolate-colors B G R 126 129 black black)
    (interpolate-colors B G R 130 255 yellow red)
    (cv/create-lookup-table B :G G :R R :table-type cv/CV_8U)))

(defn make-colormap-RYBG
  "Constructs a color map in which values from 255 to 0 map onto colors ranging from
  red to yellow to blue to green."
  []
  (let [blue (int-array [255 0 0])
        red (int-array [0 0 255])
        green (int-array [0 255 0])
        yellow (int-array [0 255 255])
        B (int-array 256)
        G (int-array 256)
        R (int-array 256)]
    (interpolate-colors B G R 0 40 green green)
    (interpolate-colors B G R 40 127 green blue)
    (interpolate-colors B G R 128 255 yellow red)
    (cv/create-lookup-table B :G G :R R :table-type cv/CV_8U)))

(defn get-color-channels
  "Constructs a hashmap that maps :red, :green, and :blue to grayscale images
  representing the R, G, and B values, respectively in the original image."
  [image]
  ;; OpenCV stores images in BGR format.
  (zipmap '(:blue :green :red) (cv/split image)))

(defn get-hsv-channels
  "Constructs a hashmap that maps :hue, :saturation, and :value to grayscale images
  representing those values in the original BGR image."
  [image]
  (zipmap '(:hue :saturation :value) (-> (cv/convert-to image cv/COLOR_BGR2HSV)
                                         cv/split)))
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn calc-color-difference-rmse [m1 m2]
    (let [diff (mapv - (mapv #(/ % 256.0) (.val m1)) (mapv #(/ % 256.0) (.val m2)))
          sqr (mapv * diff diff)]
         (Math/sqrt (reduce + sqr))))
