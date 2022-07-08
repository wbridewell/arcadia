(ns
  ^{:doc
    "This file provides clojure methods for many commonly used OpenCV operations.
     The methods can be called on three types of OpenCV matrices:

     1) Java matrixes (org.opencv.core.Mat)
     2) numpy arrays implemented in libpython-clj
     3) CUDA GPU matrices implemented in libpython-clj

     These three types of matrices can be created by calling new-java-mat,
     new-numpy-array, or new-gpu-mat, respectively. However, once a single matrix
     has been created (either from these functions or from some other source, such
     as a sensor), code from this file can generally be run in a matrix-type agnostic
     manner. To create a new matrix, call the method new-mat, whose first argument
     will be an existing matrix of one of the three types, and an appropriate new
     matrix will be created. (That said, if you need to be certain a matrix is
     a particular type, you can use the methods ->java, ->numpy, and ->gpu to
     convert a matrix to the appropriate type.)

     The methods in this library provide several conveniences over using the java
     OpenCV functions directly.

     1) Matrix size is always specified with a [width height] vector.
     2) Matrix element values can be specified with a single number (which will
        then be used for all channels) or a vector of numbers, one for each channel.
     3) Bounding boxes are represented as {:x x :y y :width width :height height},
        where x and y are the min-x and min-y values.
     4) Any method that writes data to a destination matrix returns that matrix,
        allowing OpenCV code to be written in a functional manner.

     Despite 4), the user still has two options for writing data to an existing
     destination matrix as a side effect, if that is desired.
     1) Use the optional keyword argument :dst to specify a destination matrix.
     2) Use a variant of the method with a ! on the end, to indicate that the
        method's first argument, the source matrix, is also the destination matrix.

     For an example, consider the following:
     (-> src (multiply 3) (add 4) (cvt-color COLOR_BGR2HSV))
     vs
     (-> src (multiply 3 :dst dst) (add 4 :dst dst) (cvt-color COLOR_BGR2HSV :dst dst))
     vs
     (-> src (multiply! 3) (add! 4) (cvt-color! COLOR_BGR2HSV))

     All of these will return a matrix with the same values (take the source matrix,
     multiply all values by 3, add 4 to all values, and convert from BGR to HSV).
     However, in the first case, a new destination matrix will be allocated for each
     operation. In the second case, the results of each operation will be written
     to dst, some previously created matrix. In the third case, the results of each
     operation will be written back onto src.

     While browsing through this file, note that most OpenCV methods are in alphabetical
     order and use the same name as their java or python equivalents (but with hyphens
     instead of camel case). A couple additional methods that might be useful:
     get-value/set-value: Check or change a single element in a matrix."}
  arcadia.utility.opencv
  (:refer-clojure :exclude [merge max meta min reduce type * get])
  (:import
   [org.opencv.core Core Core$MinMaxLocResult CvType Mat MatOfInt MatOfFloat MatOfPoint
    MatOfPoint2f Point Rect Scalar Size TermCriteria]
   [org.opencv.video DISOpticalFlow Video]
   org.opencv.imgproc.Imgproc
   org.opencv.imgcodecs.Imgcodecs
   [java.util ArrayList])
  (:require [arcadia.vision.regions :as reg]))

(def ^:private python-cv? "Has cv2 been loaded for python?" (atom false))
(def ^:private python-cuda? "Has the cv2 cuda library been loaded for python?" (atom false))

;;Attempt to load the cv2 and cuda libraries for python, setting python-cv?
;;and python-cuda? to true as each step succeeds.
(try
  (require '[libpython-clj2.require :refer [require-python]]
           '[libpython-clj2.python :as py]
           'libpython-clj2.python.np-array
           '[tech.v3.datatype :as dtype]
           '[tech.v3.tensor :as dtt])
  ((resolve 'require-python) '[builtins])
  ((resolve 'require-python) '[numpy :as np])
  ((resolve 'require-python) '[cv2 :as cv2])
  (reset! python-cv? true)

  ((resolve 'require-python) '[cv2.cuda :as cuda])
  ((resolve 'require-python) '[cv2.cuda_DenseOpticalFlow :as cuda-optflow])
  ((resolve 'require-python) '[cv2.cuda_FarnebackOpticalFlow :as cuda-farneback])
  (reset! python-cuda? true)
  (catch Exception e nil))

(defmacro wp
  "Code gets runs only if libpython-clj and the python cv library are available."
  [code]
  (when @python-cv?
    code))

(defmacro defmethod-py
  "def-method if libpython-clj and the python cv library are available."
  [& code]
  (when @python-cv?
    `(defmethod ~@code)))

(defmacro defmethod-cuda "def-method if opencv is compiled with cuda libraries."
  [& code]
  (when @python-cuda?
    `(defmethod ~@code)))

; (defmacro defmulti
;   "Similar to defmulti, but automatically adds a defmethod that dispatches off the
;    the first argument being a :meta-mat, i.e., a matrix combined with metadata."
;   [name docstring dispatch-fn]
;   `(do (defmulti ~name ~docstring ~dispatch-fn)
;      (defmethod ~name :meta-mat [src# & rest#]
;        (apply ~name (:matrix src#) (map #(or (:matrix %) %) rest#)))))

(def BORDER_CONSTANT Core/BORDER_CONSTANT)
(def BORDER_DEFAULT Core/BORDER_DEFAULT)
(def BORDER_ISOLATED Core/BORDER_ISOLATED)
(def BORDER_REFLECT Core/BORDER_REFLECT)
(def BORDER_REFLECT_101 Core/BORDER_REFLECT_101)
(def BORDER_REPLICATE Core/BORDER_REPLICATE)
(def COLOR_BGR2GRAY Imgproc/COLOR_BGR2GRAY)
(def COLOR_GRAY2BGR Imgproc/COLOR_GRAY2BGR)
(def COLOR_BGR2RGB Imgproc/COLOR_BGR2RGB)
(def COLOR_RGB2BGR Imgproc/COLOR_RGB2BGR)
(def COLOR_BGR2HLS Imgproc/COLOR_BGR2HLS)
(def COLOR_BGR2HSV Imgproc/COLOR_BGR2HSV)
(def COLOR_BGR2Lab Imgproc/COLOR_BGR2Lab)
(def COLOR_HSV2BGR Imgproc/COLOR_HSV2BGR)
(def COLORMAP_AUTUMN Imgproc/COLORMAP_AUTUMN)
(def COLORMAP_BONE Imgproc/COLORMAP_BONE)
(def COLORMAP_JET Imgproc/COLORMAP_JET)
(def COLORMAP_WINTER Imgproc/COLORMAP_WINTER)
(def COLORMAP_RAINBOW Imgproc/COLORMAP_RAINBOW)
(def COLORMAP_OCEAN Imgproc/COLORMAP_OCEAN)
(def COLORMAP_SUMMER Imgproc/COLORMAP_SUMMER)
(def COLORMAP_SPRING Imgproc/COLORMAP_SPRING)
(def COLORMAP_TWILIGHT Imgproc/COLORMAP_TWILIGHT)
(def COLORMAP_COOL Imgproc/COLORMAP_COOL)
(def COLORMAP_HSV Imgproc/COLORMAP_HSV)
(def COLORMAP_PINK Imgproc/COLORMAP_PINK)
(def COLORMAP_HOT Imgproc/COLORMAP_HOT)
(def COLORMAP_PARULA Imgproc/COLORMAP_PARULA)
(def COLORMAP_MAGMA Imgproc/COLORMAP_MAGMA)
(def COMP_BHATTACHARYYA Imgproc/CV_COMP_BHATTACHARYYA)
(def COMP_CHISQR Imgproc/CV_COMP_CHISQR)
(def COMP_CORREL Imgproc/CV_COMP_CORREL)
(def COMP_INTERSECT Imgproc/CV_COMP_INTERSECT)
(def CV_8U CvType/CV_8U)
(def CV_8UC1 CvType/CV_8UC1)
(def CV_8UC2 CvType/CV_8UC2)
(def CV_8UC3 CvType/CV_8UC3)
(def CV_8UC4 CvType/CV_8UC4)
(def CV_8S CvType/CV_8S)
(def CV_8SC1 CvType/CV_8SC1)
(def CV_8SC2 CvType/CV_8SC2)
(def CV_8SC3 CvType/CV_8SC3)
(def CV_8SC4 CvType/CV_8SC4)
(def CV_16U CvType/CV_16U)
(def CV_16UC1 CvType/CV_16UC1)
(def CV_16UC2 CvType/CV_16UC2)
(def CV_16UC3 CvType/CV_16UC3)
(def CV_16UC4 CvType/CV_16UC4)
(def CV_16S CvType/CV_16S)
(def CV_16SC1 CvType/CV_16SC1)
(def CV_16SC2 CvType/CV_16SC2)
(def CV_16SC3 CvType/CV_16SC3)
(def CV_16SC4 CvType/CV_16SC4)
(def CV_32F CvType/CV_32F)
(def CV_32FC1 CvType/CV_32FC1)
(def CV_32FC2 CvType/CV_32FC2)
(def CV_32FC3 CvType/CV_32FC3)
(def CV_32FC4 CvType/CV_32FC4)
(def CV_32S CvType/CV_32S)
(def CV_32SC1 CvType/CV_32SC1)
(def CV_32SC2 CvType/CV_32SC2)
(def CV_32SC3 CvType/CV_32SC3)
(def CV_32SC4 CvType/CV_32SC4)
(def CV_64F CvType/CV_64F)
(def CV_64FC1 CvType/CV_64FC1)
(def CV_64FC2 CvType/CV_64FC2)
(def CV_64FC3 CvType/CV_64FC3)
(def CV_64FC4 CvType/CV_64FC4)
(def DIS_FAST DISOpticalFlow/PRESET_FAST)
(def DIS_MEDIUM DISOpticalFlow/PRESET_MEDIUM)
(def DIS_ULTRAFAST DISOpticalFlow/PRESET_ULTRAFAST)
(def FILLED Core/FILLED)
(def FONT_HERSHEY_SIMPLEX Imgproc/FONT_HERSHEY_SIMPLEX)
(def FONT_HERSHEY_PLAIN Imgproc/FONT_HERSHEY_PLAIN)
(def FONT_HERSHEY_DUPLEX Imgproc/FONT_HERSHEY_DUPLEX)
(def INTER_NEAREST Imgproc/INTER_NEAREST)
(def INTER_LINEAR Imgproc/INTER_LINEAR)
(def INTER_LINEAR_EXACT Imgproc/INTER_LINEAR_EXACT)
(def INTER_CUBIC Imgproc/INTER_CUBIC)
(def INTER_AREA Imgproc/INTER_AREA)
(def INTER_MAX Imgproc/INTER_MAX)
(def KMEANS_PP_CENTERS KMEANS_PP_CENTERS)
(def KMEANS_RANDOM_CENTERS Core/KMEANS_RANDOM_CENTERS)
(def KMEANS_USE_INITIAL_LABELS Core/KMEANS_USE_INITIAL_LABELS)
(def LINE_4 Imgproc/LINE_4)
(def LINE_8 Imgproc/LINE_8)
(def LINE_AA Imgproc/LINE_AA)
(def MORPH_CLOSE Imgproc/MORPH_CLOSE)
(def MORPH_CROSS Imgproc/MORPH_CROSS)
(def MORPH_DILATE Imgproc/MORPH_DILATE)
(def MORPH_ELLIPSE Imgproc/MORPH_ELLIPSE)
(def MORPH_ERODE Imgproc/MORPH_ERODE)
(def MORPH_GRADIENT Imgproc/MORPH_GRADIENT)
(def MORPH_HITMISS Imgproc/MORPH_HITMISS)
(def MORPH_OPEN Imgproc/MORPH_OPEN)
(def NORM_HAMMING Core/NORM_HAMMING)
(def NORM_HAMMING2 Core/NORM_HAMMING2)
(def NORM_INF Core/NORM_INF)
(def NORM_L1 Core/NORM_L1)
(def NORM_L2 Core/NORM_L2)
(def NORM_L2SQR Core/NORM_L2SQR)
(def NORM_MINMAX Core/NORM_MINMAX)
(def NORM_RELATIVE Core/NORM_RELATIVE)
(def NORM_TYPE_MASK Core/NORM_TYPE_MASK)
(def REDUCE_AVG Core/REDUCE_AVG)
(def REDUCE_MAX Core/REDUCE_MAX)
(def REDUCE_MIN Core/REDUCE_MIN)
(def REDUCE_SUM Core/REDUCE_SUM)
(def RETR_CCOMP Imgproc/RETR_CCOMP)
(def RETR_EXTERNAL Imgproc/RETR_EXTERNAL)
(def RETR_LIST Imgproc/RETR_LIST)
(def RETR_TREE Imgproc/RETR_TREE)
(def ROTATE_180 Core/ROTATE_180)
(def ROTATE_90_CLOCKWISE Core/ROTATE_90_CLOCKWISE)
(def ROTATE_90_COUNTERCLOCKWISE Core/ROTATE_90_COUNTERCLOCKWISE)
(def THRESH_BINARY Imgproc/THRESH_BINARY)
(def THRESH_BINARY_INV Imgproc/THRESH_BINARY_INV)
(def THRESH_MASK Imgproc/THRESH_MASK)
(def THRESH_TOZERO Imgproc/THRESH_TOZERO)
(def THRESH_TOZERO_INV Imgproc/THRESH_TOZERO_INV)
(def THRESH_TRIANGLE Imgproc/THRESH_TRIANGLE)
(def THRESH_TRUNC Imgproc/THRESH_TRUNC)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Fns for adding metadata to matrices

; (defn meta
;   "Returns the metadata for a matrix."
;   [src]
;   (:meta src))
;
; (defn with-meta
;   "Adds metadata (a hashmap) to a matrix."
;   [src metadata]
;   (if (map? src)
;     (with-meta (:matrix src) metadata)
;     {:matrix src :meta metadata}))
;
; (defn vary-meta
;   "Calls (apply f (meta src) args) on a matrix and returns the matrix
;    with updated metadata."
;   [src f & args]
;   (if (map? src)
;     (update src :meta #(apply f % args))
;     (apply vary-meta {:matrix src :meta {}} f args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Fns for convert type/depth/channels information between different formats

(defn- type->depth
  "Convert an OpenCV type to depth, e.g., CV_8UC3 => CV_8U."
  [type]
  (mod type 8))

(defn- type->channels
  "Convert an OpenCV type to a number of channels, e.g., CV_8UC3 => 3."
  [type]
  (inc (bit-shift-right type 3)))

(defn- type->dtype
  "Convert an OpenCV type to a numpy dtype (as a string)."
  [type]
  (condp = (type->depth type)
    CV_8U "uint8"
    CV_8S "int8"
    CV_16U "uint16"
    CV_16S "int16"
    CV_32S "int32"
    CV_32F "float32"
    CV_64F "float64"))

(defn- dtype->depth
  "Convert a numpy dtype (as a string) to an OpenCV depth value."
  [dtype]
  (case dtype
    "uint8" CV_8U
    "int8" CV_8S
    "uint16" CV_16U
    "int16" CV_16S
    "int32" CV_32S
    "float32" CV_32F
    "float64" CV_64F))

(defn- depth->symbol
  "Convert a mat's depth or type to a depth symbol."
  [type]
  (condp = (type->depth type)
    CV_8U :8U
    CV_8S :8S
    CV_16U :16U
    CV_16S :16S
    CV_32S :32S
    CV_32F :32F
    CV_64F :64F))

(defn- jtype->depth
  "Gets the corresponding OpenCV depth for a java number type."
  [jtype]
  (condp = jtype
    Byte/TYPE CV_8U
    Short/TYPE CV_16S
    Integer/TYPE CV_32S
    Float/TYPE CV_32F
    Double/TYPE CV_64F))

(defn- type->jtype
  "Gets the corresponding java number type for an OpenCV type or depth."
  [type]
  (condp = (type->depth type)
    CV_8U Byte/TYPE
    CV_8S Byte/TYPE
    CV_16S Short/TYPE
    CV_32S Integer/TYPE
    CV_32F Float/TYPE
    CV_64F Double/TYPE))

(defn- type->arrayfn
  "Gets the corresponding java array function for an OpenCV type or depth."
  [type]
  (condp = (type->depth type)
    CV_8U byte-array
    CV_16S short-array
    CV_32S int-array
    CV_32F float-array
    CV_64F double-array))

(wp (defn- type->dtypefn
      "Gets the corresponding dtype->array function for an OpenCV type or depth."
      [type]
      (condp = (type->depth type)
        CV_8U dtype/->byte-array
        CV_16S dtype/->short-array
        CV_32S dtype/->int-array
        CV_32F dtype/->float-array
        CV_64F dtype/->double-array)))

(defn- depth+channels->type
  "Converts an OpenCV depth and a number of channels to an OpenCV type."
  [depth channels]
  (+ (type->depth depth) (bit-shift-left (dec channels) 3)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Helper Fns

(defn mat-type "Returns a symbol for the type of matric, e.g., :java-mat."
  [mat]
  (condp = (clojure.core/type mat)
    Mat :java-mat
    nil))

(wp
 (defn mat-type "Returns a symbol for the type of matric, e.g., :java-mat."
   [mat]
   (condp = (clojure.core/type mat)
     Mat :java-mat
     :pyobject (#{:ndarray :cuda-gpu-mat} (py/python-type mat))
     nil)))

(defn general-mat-type
  "Returns a symbol for the type of matric, e.g., :java-mat. Generalizes over
   different types of java matrices, e.g., both Mat and MatOfPoint are :java-mat"
  [mat]
  (when (instance? Mat mat)
    :java-mat))

(wp
 (defn general-mat-type
   "Returns a symbol for the type of matric, e.g., :java-mat. Generalizes over
   different types of java matrices, e.g., both Mat and MatOfPoint are :java-mat"
   [mat]
   (cond
     (instance? Mat mat) :java-mat
     (= (clojure.core/type mat) :pyobject)
     (#{:ndarray :cuda-gpu-mat} (py/python-type mat))
     :else nil)))

(defn mat-name "Returns a string for the type of matrix."
  [mat]
  (case (mat-type mat)
    :java-mat "Mat"
    :ndarray "NP-Array"
    :cuda-gpu-mat "GPU-Mat"
    ; :meta-mat (mat-name (:matrix mat))
    nil))

(wp (defn- np-shape "Shape of a numpy array."
      [src]
      (vec (py/get-attr src :shape))))

(wp (defn- gpu-size "Size of a gpu mat."
      [src]
      (vec (py/$a src size))))

(defn- java-scalar
 "Takes either a single number or a vector of numbers and returns a Scalar. If the
  input is anything else, simply returns it."
 [input]
 (cond
   (number? input)
   (Scalar/all input)

   (vector? input)
   (let [[a b c d] input]
     (Scalar. a (or b 0) (or c 0) (or d 0)))

   :else (or (:matrix input) input)))

(defn- python-scalar
 "Takes either a single number or a vector of numbers and returns a vector
  representing a scalar for python. If the input is anything else, simply returns
  it."
 [input]
 (cond
   (number? input)
   (vector input input input input)

   (vector? input)
   (let [[a b c d] input]
     (vector a (or b 0) (or c 0) (or d 0)))

   :else (or (:matrix input) input)))

(defn- python-scalar2
 "Separate version of python-scalar for numpy operations because they want the
  number of elements in the vector to be equal to the number of channels. But a
  single number works fine for any number of channels."
 [input channels]
 (if (vector? input)
   (let [diff (- (count input) channels)]
     (cond
       (zero? diff) input
       (pos? diff) (vec (take channels input))
       (neg? diff) (vec (concat input (repeat (- diff) 0)))))
   (or (:matrix input) input)))

(defn- java-value
  "Takes a java Scalar object and a source matrix and returns a clojure value for
   the Scalar, either a single number if the matrix is single-channel or a
   vector of numbers if the matrix is multi-chanel."
  [scalar ^Mat src]
  (let [channels (.channels src)]
    (if (= channels 1)
      (-> scalar (.val) (aget 0))
      (->> scalar (.val) seq (take channels) vec))))

(wp (defn- python-value
  "Takes a python ccalar (a tuple) and a source matrix and returns a clojure value
   for the scalar, either a single number if the matrix is single-channel or a
   vector of numbers if the matrix is multi-chanel."
  [scalar src]
  (if (< (py/get-attr src :ndim) 3)
    (-> scalar vec first)
    (->> scalar vec (take (nth (np-shape src) 2)) vec))))

(defn- poll-pixel
  "Returns a single pixel for for a java-mat. It's type and numbe of channels can
   be optionally specified, or they can determined automatically. Returns a single
   number for a single-channel array, or a vector of numbers for multi-channel."
  ([^Mat img x y]
   (poll-pixel img x y (.type img) (.channels img)))
  ([^Mat img x y type]
   (poll-pixel img x y type (.channels img)))
  ([^Mat img x y type channels]
   (let [jtype (type->jtype type)
         temp (make-array jtype channels)]
     (.get img y x temp)
     (if (> channels 1)
       (cond->> (vec temp)
               (= jtype Byte/TYPE) (mapv #(mod % 256))) ;;If it's a byte, convert to unsigned
       (cond-> (clojure.core/get temp 0)
               (= jtype Byte/TYPE) (mod 256))))))

(defn- contour-convex-hull
  "Takes a contour, a MatOfPoints, and returns a new contour consisting of the
   original's convex hull. Used only for java matrices."
  [contour]
  (let [d (MatOfInt.)]
    (Imgproc/convexHull contour d)
    (let [inds (.toArray d)
          pts (.toArray contour)
          new-pts (java.util.ArrayList.)
          new-contour (MatOfPoint.)]
      (dotimes [i (count inds)]
        (.add new-pts (clojure.core/get pts (clojure.core/get inds i))))
      (.fromList new-contour new-pts)
      new-contour)))

(defmulti contour->segment
  "Converts a contour (a list of points) into a segment, a hashmap of the form
   {:region region :area area :mask mask}. ref-mat indicates whether we're working
   on contours from a java mat or a numpy array. Input is
   [ref-mat contour]"
  (fn [ref-mat & rest] (mat-type ref-mat)))

(defmethod contour->segment :java-mat
  [ref-mat contour]
  (let [rect (Imgproc/boundingRect contour)
        x (.x rect) y (.y rect) width (.width rect) height (.height rect)
        mat (Mat/zeros (+ y height) (+ x width) CV_8UC1)]
    (Imgproc/drawContours mat (ArrayList. (list contour)) -1 (Scalar. 255 255 255) Core/FILLED)
    {:region {:x x :y y :width width :height height}
     :contour contour
     :area (Imgproc/contourArea contour)
     :mask (.submat mat rect)}))

(declare submat)
(defmethod-py contour->segment :ndarray
  [ref-mat contour]
  (let [[x y w h] (vec (cv2/boundingRect contour))
        region {:x x :y y :width w :height h}
        mat (np/full [(+ y h) (+ x w)] 0 "uint8")]
    (cv2/drawContours mat [contour] -1 [255 255 255] Core/FILLED)
    {:region region
     :contour contour
     :area (cv2/contourArea contour)
     :mask (submat mat region)}))

(declare get-value)
(defn- contours->segments
  "Converts a list of contours and the accompanying hierarchy object into a list of
   segments. If the hierarchy object indicates a nested hierarchy (contours within
   contours), this will be reflected in a :subsegments field for each segment."
  ([contours hierarchy ref-mat]
   (contours->segments contours hierarchy 0 ref-mat))
  ([contours hierarchy index ref-mat]
   (when (seq contours)
     (let [[next _ child] (and hierarchy (get-value hierarchy index 0))]
       (lazy-seq
        (cons
         (assoc (contour->segment ref-mat (clojure.core/get contours index))
                :subsegments
                (when (and child (>= child 0))
                  (contours->segments contours hierarchy child ref-mat)))
         (when (and next (>= next 0))
           (contours->segments contours hierarchy next ref-mat))))))))

(defn- dst-for-java-mat
  "Given a src matrix and a dst-type, returns an empty java matrix of the
   appropriate type."
  [src dst-type]
  (let [mtype (clojure.core/type src)]
    (cond
      (= mtype Mat) (Mat.)

      (or (= mtype MatOfPoint) (= mtype MatOfPoint2f))
      (condp = (type->depth dst-type)
        CV_32S (MatOfPoint.)
        CV_32F (MatOfPoint2f.))

      :else (throw (Exception. "Unable to determine dst matrix type.")))))

(declare depth)
(defn- default-interp
  "Select a default interpolation method for resizing a matrix."
  [src]
  (let [depth (depth src)]
    (if (or (= depth CV_8S) (= depth CV_32S))
      INTER_NEAREST
      INTER_LINEAR)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Constructors

(defn new-java-mat
  "Returns a newly created java matrix. Input options are
   []
   [size type]
   [size type init-value]"
  ([[width height] type init-value]
   (Mat. height width type (java-scalar init-value)))
  ([[width height] type]
   (Mat. height width type))
  ([]
   (Mat.)))

(wp
 (defn new-numpy-array
   "Returns a newly created numpy array. Input options are
   []
   [[width height] type]
   [[width height] type init-value]"
   ([[width height] type init-value]
    (let [channels (type->channels type)
          dtype (type->dtype type)]
      (if (> channels 1)
        (np/full [height width channels] (python-scalar2 init-value channels) dtype)
        (np/full [height width] (python-scalar2 init-value channels) dtype))))
   ([[width height] type]
    (let [channels (type->channels type)
          dtype (type->dtype type)]
      (if (> channels 1)
        (np/empty [height width channels] dtype)
        (np/empty [height width] dtype))))
   ([]
    (np/empty [0 0]))))

(wp
 (defn new-gpu-mat
   "Returns a newly created gpu matrix. Input options are
   []
   [size type]
   [sizde type init-value]"
   ([[width height] type init-value]
    (cv2/cuda_GpuMat height width type (python-scalar init-value)))
   ([[width height] type]
    (cv2/cuda_GpuMat height width type))
   ([]
    (cv2/cuda_GpuMat))))


(defn imread-java-mat "Read in an image file and return a Java OpenCV matrix."
  [path]
  (Imgcodecs/imread path))

(wp
 (defn imread-numpy-array "Read in an image file and return a numpy array in python."
   [path]
   (cv2/imread path)))


(defmulti new-mat
  "Returns a new matrix of the same matrix type as src. Optional keys are
   :size Size of the matrix. Will use src's size if this isn't provided.
   :type Type of the matrix. Will use src's type if this isn't provided.
   :depth Depth of the matrix. Will be used only if :type isn't provided.
   :channels Channels for the matrix. Will be used only if :type isn't provided.
   :value Initial value for all elements. Elements will not be initialized
               to anything if this isn't provided.
   Input is
   [src {:size :type :depth :channels :value}]"
  (fn [src & rest] (mat-type src)))

(defmethod new-mat :java-mat
  [src & {:keys [size type depth channels value]}]
  (let [size (or (and size (Size. (first size) (second size)))
                 (.size src))
        type (or type
                 (and (or depth channels)
                      (depth+channels->type (or depth (.depth src))
                                            (or channels (.channels src))))
                 (and depth (depth+channels->type depth (.channels src)))
                 (.type src))]
    (if value
      (Mat. size type (java-scalar value))
      (Mat. size type))))

(defmethod-py new-mat :ndarray
  [src & {:keys [size type depth channels value]}]
  (let [dtype (or (and type (type->dtype type))
                  (and depth (type->dtype depth))
                  (py/get-attr src :dtype))
        [src-height src-width src-channels]
        (when (or (nil? size) (and (nil? type) (nil? channels)))
          (np-shape src))
        height (or (second size) src-height)
        width (or (first size) src-width 1)
        channels (or (and type (type->channels type))
                     channels
                     src-channels 1)
        shape (if (> channels 1)
                [height width channels]
                [height width])]
    (if value
      (np/full shape (python-scalar2 value channels) dtype)
      (np/empty shape dtype))))

(defmethod-cuda new-mat :cuda-gpu-mat
  [src & {:keys [size type depth channels value]}]
  (let [size (or size (gpu-size src))
        type (or type
                 (and (or depth channels)
                      (depth+channels->type (or depth (py/$a src depth))
                                            (or channels (py/$a src channels))))
                 (py/$a src type))]
    (if value
      (cv2/cuda_GpuMat size type (python-scalar value))
      (cv2/cuda_GpuMat size type))))


(defmulti ones
    "Creates a new matrix, with all its values initialized to 1. Can be called in
     two ways.
     1) The first argument is a ref-mat, and :size, specified as [width height],
        and :type can be optionally provided. The output will be the same matrix
        type as ref-mat (e.g., java-mat vs. numpy array), and size and type will
        the same as ref-mat if values are not specified.
     2) No ref-mat is provided, in which case a java-mat will be created with
        the specified size and type. Input options are
     [ref-mat {:size :type}]
     [size type]"
  (fn [ref-mat & rest] (some? (mat-type ref-mat))))

(defmethod ones true
  [ref-mat & {:keys [size type]}]
  (new-mat ref-mat :size size :type type :value 1))

(defmethod ones false
  [size type]
  (new-java-mat size type 1))


(defmulti zeros
    "Creates a new matrix, with all its values initialized to 0. Can be called in
     two ways.
     1) The first argument is a ref-mat, and :size, specified as [width height],
        and :type can be optionally provided. The output will be the same matrix
        type as ref-mat (e.g., java-mat vs. numpy array), and size and type will
        the same as ref-mat if values are not specified.
     2) No ref-mat is provided, in which case a java-mat will be created with
        the specified size and type. Input options are
     [ref-mat {:size :type}]
     [size type]"
  (fn [ref-mat & rest] (some? (mat-type ref-mat))))

(defmethod zeros true
  [ref-mat & {:keys [size type]}]
  (new-mat ref-mat :size size :type type :value 0))

(defmethod zeros false
  [size type]
  (new-java-mat size type 0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Conversions between matrix types

(defmulti ->numpy "Converts a matrix to a numpy array in python."
  (fn [src] (mat-type src)))

(defmethod-py ->numpy :java-mat [src]
  (let [width (.width src)
        height (.height src)
        channels (.channels src)
        depth (.depth src)
        data (make-array (type->jtype depth) (clojure.core/* width height channels))]
    (.get src 0 0 data)

    (cond-> (-> data dtt/ensure-tensor py/->python)
            true (py/$a astype (type->dtype depth))
            (> channels 1) (np/reshape [height width channels])
            (= channels 1) (np/reshape [height width]))))

(defmethod ->numpy :ndarray [src]
  src)

(defmethod-cuda ->numpy :cuda-gpu-mat [src]
  (py/$a src download))

(defmethod-py ->numpy nil [src]
  (let [cl (class src)]
    (if (and cl (.isArray cl))
      (py/$a (-> src dtt/ensure-tensor py/->python) astype
             (-> cl (.getComponentType) jtype->depth type->dtype))
      src)))


(defmulti ->gpu "Converts a matrix to a gpu matrix in python."
  (fn [src] (mat-type src)))

(defmethod-cuda ->gpu :java-mat [src]
  (cv2/cuda_GpuMat (->numpy src)))

(defmethod-cuda ->gpu :ndarray [src]
  (cv2/cuda_GpuMat src))

(defmethod ->gpu :cuda-gpu-mat [src]
  src)

(defmethod-cuda ->gpu nil [src]
  (if (some-> src class (.isArray))
    (cv2/cuda_GpuMat (->numpy src))
    src))


  (defmulti ->java
  "Converts a matrix to a java mat. By default, the java mat will have the same
   depth, [width height] size, and channels as src, but different values can be
   specified (note that these values only have an effect if you are converting from
   a numpy array or gpu matrix).
   Input is
   [src {:depth :size :channels}]"
  (fn [src & rest] (mat-type src)))

(defmethod ->java :java-mat [src & rest]
  src)

(defmethod-py ->java :ndarray
  [src & {:keys [depth type size channels]}]
  (let [[width height] size
        [old-height old-width old-channels] (np-shape src)
        height (or height old-height)
        width (or width old-width 1)
        channels (or channels (and type (type->channels type)) old-channels 1)
        old-depth (-> (py/get-attr src :dtype) str dtype->depth)
        depth (or depth (and type (type->depth type)) old-depth)
        dst (Mat. height width (or type (depth+channels->type depth channels)))]
    (if (= old-depth CvType/CV_8U)
      (->> (py/$a (py/$a src :astype "int8") :ravel)
           dtt/ensure-tensor dtype/->byte-array (.put dst 0 0))
      (->> (py/$a src :ravel) dtt/ensure-tensor ((type->dtypefn depth)) (.put dst 0 0)))
    dst))

(defmethod-cuda ->java :cuda-gpu-mat [src & rest]
  (apply ->java (py/$a src download) rest))

(defmethod ->java nil [src]
  (let [cl (class src)]
    (if (and cl (.isArray cl))
      (let [dst (Mat. (count src) 1 (-> cl (.getComponentType) jtype->depth))]
        (.put dst 0 0 src)
        dst)
      src)))


(defmulti ->array "Converts a matrix to a one-dimensional java array."
  (fn [src] (general-mat-type src)))

(defmethod ->array :java-mat
  [src]
  (let [dst (make-array (type->jtype (.type src))
                        (clojure.core/* (.total src) (.channels src)))]
    (.get src 0 0 dst)
    dst))

(defmethod-py ->array :ndarray [src]
  (let [depth (-> (py/get-attr src :dtype) str (dtype->depth))]
    (if (= depth CvType/CV_8U)
      (->> (py/$a src astype "int8") dtype/->byte-array)
      (-> src ((type->dtypefn depth))))))

(defmethod-cuda ->array :cuda-gpu-mat [src]
  (->array (py/$a src download)))


(defmulti ->mat
  "Converts src to an opencv matrix of the same matrix type as ref-mat. For example,
   if ref-mat is a numpy array, converts src to a numpy array. Input is
   [ref-mat src]"
  (fn [ref-mat & rest] (mat-type ref-mat)))

(defmethod ->mat :java-mat [_ src]
  (->java src))

(defmethod-py ->mat :ndarray [_ src]
  (->numpy src))

(defmethod-cuda ->mat :cuda-gpu-mat [_ src]
  (->gpu src))


(defn- java->seq
  "Helper fn for ->seq when operating on java mats."
  [src x y width height]
  (cond
    (or (>= x width) (>= y height))
    nil

    (zero? x)
    (lazy-seq (cons (lazy-seq (cons (poll-pixel src x y) (java->seq src (inc x) y width height)))
                    (java->seq src x (inc y) width height)))

    :else
    (lazy-seq (cons (poll-pixel src x y) (java->seq src (inc x) y width height)))))

(wp (defn- numpy->seq
  "Helper fn for ->seq when operating on numpy arrays."
  [src x y width height]
  (cond
    (or (>= x width) (>= y height))
    nil

    (zero? x)
    (lazy-seq (cons (lazy-seq (cons (py/get-item src [y x]) (numpy->seq src (inc x) y width height)))
                    (numpy->seq src x (inc y) width height)))

    :else
    (lazy-seq (cons (py/get-item src [y x]) (numpy->seq src (inc x) y width height))))))

(defmulti ->seq
  "Converts src to a lazy sequence of numbers. This will be a nested sequence,
   with one sequence for each row. If src is multi-channel, there will also be a
   vector for each value."
  (fn [src] (general-mat-type src)))

(defmethod ->seq :java-mat [^Mat src]
  (java->seq src 0 0 (.width src) (.height src)))

(defmethod-py ->seq :ndarray [src]
  (let [[width height] (np-shape src)]
    (numpy->seq src 0 0 width height)))

(defmethod-cuda ->seq :cuda-gpu-mat [src]
  (->seq (->numpy src)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Accessing size information about matrices and other objects

(defmulti area
  "Based on .size
   -----------------------------------------------------------------------
   Returns the (* width height) area of a matrix."
  (fn [src] (or (mat-type src) (clojure.core/type src))))

(defmethod area :java-mat [src]
  (clojure.core/* (.width src) (.height src)))

(defmethod-py area :ndarray [src]
  (let [[height width] (np-shape src)]
    (clojure.core/* (or width 1) height)))

(defmethod-cuda area :cuda-gpu-mat [src]
  (let [[height width] (gpu-size src)]
    (clojure.core/* (or width 1) height)))

(defmethod area clojure.lang.PersistentVector [[w h]]
  (clojure.core/* w h))

(defmethod area clojure.lang.PersistentHashMap [{w :width x :x h :height y :y}]
  (clojure.core/* (or w (and x 1)) (or h (and y 1))))

(defmethod area clojure.lang.PersistentArrayMap [{w :width x :x h :height y :y}]
  (clojure.core/* (or w (and x 1)) (or h (and y 1))))


(defmulti height
  "Based on .height
   -----------------------------------------------------------------------
   Returns the height of a matrix."
  (fn [src] (or (general-mat-type src) (clojure.core/type src))))

(defmethod height :java-mat [src]
  (.height src))

(defmethod-py height :ndarray [src]
  (first (np-shape src)))

(defmethod-cuda height :cuda-gpu-mat [src]
  (second (gpu-size src)))

(defmethod height clojure.lang.PersistentVector [[_ h]]
  h)

(defmethod height clojure.lang.PersistentHashMap [{h :height y :y}]
  (or h (and y 1)))

(defmethod height clojure.lang.PersistentArrayMap [{h :height y :y}]
  (or h (and y 1)))


(defmulti size
  "Based on .size
   -----------------------------------------------------------------------
   Returns the [width height] size of a matrix."
  (fn [src] (or (general-mat-type src) (clojure.core/type src))))

(defmethod size :java-mat [src]
  [(.width src) (.height src)])

(defmethod-py size :ndarray [src]
  (let [[height width] (np-shape src)]
    [(or width 1) height]))

(defmethod-cuda size :cuda-gpu-mat [src]
  (gpu-size src))

(defmethod size clojure.lang.PersistentVector [[w h]]
  [w h])

(defmethod size clojure.lang.PersistentHashMap [{w :width x :x h :height y :y}]
  [(or w (and x 1)) (or h (and y 1))])

(defmethod size clojure.lang.PersistentArrayMap [{w :width x :x h :height y :y}]
  [(or w (and x 1)) (or h (and y 1))])


(defmulti width
  "Based on .width
   -----------------------------------------------------------------------
   Returns the width of a matrix."
  (fn [src] (or (general-mat-type src) (clojure.core/type src))))

(defmethod width :java-mat [src]
  (.width src))

(defmethod-py width :ndarray [src]
  (let [[_ width] (np-shape src)]
    (or width 1)))

(defmethod-cuda width :cuda-gpu-mat [src]
  (first (gpu-size src)))

(defmethod width clojure.lang.PersistentVector [[w]]
  w)

(defmethod width clojure.lang.PersistentHashMap [{w :width x :x}]
  (or w (and x 1)))

(defmethod width clojure.lang.PersistentArrayMap [{w :width x :x}]
  (or w (and x 1)))


(defn- x-y-w-h
  "Takes a hashmap and a reference object and returns the hashmap's
   [x y width height] as a vector. If the hashmap's width is undefined but x is
   defined, assumes width is 1. If both are undefined, assumes the hashmap ranges
   from 0 to (width ref-object). Does the same for y/height. Input options are
   [obj ref-obj]
   [x y width height ref-obj]"
  ([{x :x y :y w :width h :height} ref-obj]
  [(or x 0) (or y 0)
   (or w (and x 1) (width ref-obj)) (or h (and y 1) (height ref-obj))])
  ([x y w h ref-obj]
   [(or x 0) (or y 0)
    (or w (and x 1) (width ref-obj)) (or h (and y 1) (height ref-obj))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;Everything else, in alphabetical order

(declare abs-diff)
(defn abs
  "Based on Core/absdiff
   -----------------------------------------------------------------------
   Calculates the per-element absolute value of a matrix. Input is
   [src {:dst}]"
  [src & {:keys [dst]}]
  (abs-diff src 0 :dst dst))

(defn abs!
  "Variant of abs in which src is also the destination matrix dst."
  [src & rest]
  (apply abs src (concat rest [:dst src])))


(defmulti abs-diff
  "Based on Core/absdiff
   -----------------------------------------------------------------------
   Calculates the per-element absolute difference between two matrices, or between
   a matrix and a constant (src2), where the constant can be either a number or
   a vector of numbers, one for each channel. Input is
   [src1 src2 {:dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod abs-diff :java-mat
  [^Mat src1 src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/absdiff src1 (java-scalar src2) dst)
    dst))

(defmethod-py abs-diff :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/absdiff src1 (python-scalar src2) dst))

;;python's cuda implmenetation currently doesn't support operations between a
;;matrix and a scalar, so we have to make a new cuda-gpu-mat containing the scalar value
(defmethod-cuda abs-diff :cuda-gpu-mat
  [src1 src2 & {:keys [mask dst]}]
  (if (or (number? src2) (vector? src2))
    (cuda/absdiff
     src1
     (cv2/cuda_GpuMat (gpu-size src1) (py/$a src1 type) (python-scalar src2))
     dst mask)
    (cuda/absdiff src1 src2 dst mask)))

(defn abs-diff!
  "Variant of abs-diff in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply abs-diff src1 (concat rest [:dst src1])))


(declare min!)
(defn angle-diff
  "Based on Core/absdiff
   -----------------------------------------------------------------------
   Calculates the per-element absolute difference between two matrices, or between
   a matrix and a constant (src2), where the constant can be either a number or
   a vector of numbers, one for each channel. Similar to abs-diff, but assumes the
   values are angles in degrees, such that, for example, the difference between 0
   and 360 is 0. Input is
   [src1 src2 {:dst}]"
  [src1 src2 & {:keys [dst]}]
  (let [diff (abs-diff src1 src2 :dst dst)]
    (min! diff (abs-diff diff 360))))

(defn angle-diff!
  "Variant of angle-diff in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply angle-diff src1 (concat rest [:dst src1])))


(defmulti add
  "Based on Core/add
   ---------------------------------------------------------------------------
   Add two matrices togther, or add a constant (src2) to a matrix, where the constant
   can be either a number or a vector of numbers, one for each channel. Input is
   [src1 src2 {:mask :dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod add :java-mat
  [^Mat src1 src2 & {:keys [mask dst]}]
  (let [dst (or dst (Mat.))]
    (if mask
      (Core/add src1 (java-scalar src2) dst mask)
      (Core/add src1 (java-scalar src2) dst))
    dst))

(defmethod-py add :ndarray
  [src1 src2 & {:keys [mask dst]}]
  (cv2/add src1 (python-scalar src2) dst mask))

;;adding a scalar to a cuda-gpu-mat is currently unsupported,
;;so we have to make a new cuda-gpu-mat containing the scalar value.
(defmethod-cuda add :cuda-gpu-mat
  [src1 src2 & {:keys [mask dst]}]
  (if (or (number? src2) (vector? src2))
    (cuda/add
     src1
     (cv2/cuda_GpuMat (gpu-size src1) (py/$a src1 type) (python-scalar src2))
     dst mask)
    (cuda/add src1 src2 dst mask)))

(defn add!
  "Variant of add in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply add src1 (concat rest [:dst src1])))


(defmulti add-weighted
  "Based on Core/addWeighted
   -----------------------------------------------------------------------
   Adds two matrices together, multiplying the first matrix by alpha and the
   second by beta, and optionally adding gamma to the result. Input is
   [src1 alpha src2 beta {:gamma :dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod add-weighted :java-mat
  [src1 alpha src2 beta & {:keys [gamma dst] :or {gamma 0}}]
  (let [dst (or dst (Mat.))]
    (Core/addWeighted src1 alpha src2 beta gamma dst)
    dst))

(defmethod-py add-weighted :ndarray
  [src1 alpha src2 beta & {:keys [gamma dst] :or {gamma 0}}]
  (cv2/addWeighted src1 alpha src2 beta gamma dst))

(defmethod-cuda add-weighted :cuda-gpu-mat
  [src1 alpha src2 beta & {:keys [gamma dst] :or {gamma 0}}]
  (cuda/addWeighted src1 alpha src2 beta gamma dst))

(defn add-weighted!
  "Variant of add-weighted in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply add-weighted src1 (concat rest [:dst src1])))


(defmulti apply-colormap
  "Based on Imgproc/applyColorMap
   -----------------------------------------------------------------------
   Apply a colormap to src. This converts a one or three-channel matrix to a
   (generally) color matrix of according to the colormap selected. See, for example,
   COLORMAP_AUTUMN. As an alternative, consider using create-lookup-table and
   apply-lookup-table. Input is
   [src colormap {:dst}]

   NOTE: Currently not supported for gpu mats."
  (fn [src & rest] (mat-type src)))

(defmethod apply-colormap :java-mat
  [^Mat src colormap & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Imgproc/applyColorMap src dst colormap)
    dst))

(defmethod-py apply-colormap :ndarray
  [src colormap & {:keys [dst]}]
  (cv2/applyColorMap src colormap dst))

(defn apply-colormap!
  "Variant of apply-colormap in which src is also the destination matrix dst."
  [src & rest]
  (apply apply-colormap src (concat rest [:dst src])))


(defmulti apply-lookup-table
  "Based on Core/LUT
   -----------------------------------------------------------------------
   Apply a lookup table created by create-lookup-table to src. Input is
   [src lut {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod apply-lookup-table :java-mat
  [^Mat src {table :table max-input :max-input} & {:keys [dst]}]
  (let [tmp (if max-input
              (Mat.) src)
        dst (or dst (Mat.))]
    (when max-input
      (Core/convertScaleAbs src tmp (/ 255.0 max-input)))

    (Core/LUT tmp table dst)
    dst))

(defmethod-py apply-lookup-table :ndarray
  [src {table :table max-input :max-input} & {:keys [dst]}]
  (cond-> src
          max-input (cv2/convertScaleAbs nil (/ 255.0 max-input))
          true (cv2/LUT table dst)))

(defmethod-cuda apply-lookup-table :cuda-gpu-mat
  [src {table :table max-input :max-input max-output :max-output channels :channels}
   & {:keys [dst]}]
  (let [dst (or dst (new-mat src :depth (if max-output CV_32F CV_8U) :channels channels))
        tmp1 (if max-input (new-mat src :depth CV_8U) src)
        tmp2 (when (and (= channels 3) (= (py/$a tmp1 channels) 1)) (new-mat tmp1 :channels 3))
        tmp3 (if max-output (new-mat src :depth CV_8U :channels channels) dst)]
    (cond-> src
            max-input (py/$a convertTo CV_8U (/ 255.0 max-input) tmp1)
            tmp2 ((fn [s] (cuda/merge (vector s s s) tmp2)))
            true ((fn [s] (py/$a table transform s tmp3)))
            max-output (py/$a convertTo CV_32F (/ max-output 255.0) dst))))

(defn apply-lookup-table!
  "Variant of apply-lookup-table in which src is also the destination matrix dst."
  [src & rest]
  (apply apply-lookup-table src (concat rest [:dst src])))


(defmulti approx-poly-dp
  "Based on Imgproc/approxPolyDP
   -----------------------------------------------------------------------
   Takes a matrix of points (for example, a contour) and returns a new matrix of
   points that approximates a polygon. epsilon specifies the approximation accuracy. This
   is the maximum distance between the original curve and its approximation. Input is
   [src epsilon closed?]

   NOTE: Not defined for GPU matrices."
  (fn [src & rest] (general-mat-type src)))

(defmethod approx-poly-dp :java-mat
  [src epsilon closed?]
  (let [dst (MatOfPoint2f.)]
    (if (= (.depth src) CV_32F)
    (Imgproc/approxPolyDP src dst epsilon closed?)

    (let [tmp (MatOfPoint2f.)]
      (.convertTo src tmp CV_32F)
      (Imgproc/approxPolyDP tmp dst epsilon closed?)))

    dst))

(defmethod-py approx-poly-dp :ndarray
  [src epsilon closed?]
  (cv2/approxPolyDP src epsilon closed?))


(defmulti arc-length
  "Based on Imgproc/arcLength
   -----------------------------------------------------------------------
   Takes a matrix of points (for example, a contour) and returns the arc length
   along the points. Input is
   [src closed?]

   NOTE: Not defined for GPU matrices."
  (fn [src & rest] (general-mat-type src)))

(defmethod arc-length :java-mat
  [src closed?]
  (if (= (.depth src) CV_32F)
    (Imgproc/arcLength src closed?)
    (let [dst (MatOfPoint2f.)]
      (.convertTo src dst CV_32F)
      (Imgproc/arcLength dst closed?))))

(defmethod-py arc-length :ndarray
  [src closed?]
  (cv2/arcLength src closed?))


(defmulti arrowed-line
  "Based on Imgproc/arrowedLine
   -----------------------------------------------------------------------
   Draws an arrowed line onto a matrix and returns the matrix. thickness is an integer
   and defaults to 1. tip-length is a double describing the arrow's tip relative
   to the rest of the line and defaults to 0.1. line-type defaults to LINE_8
   (use LINE_AA for antialiased).
   Points are in the form {:x x :y y}. Color can be a single number or a vector
   of numbers, one for each channel. Input is
   [src point1 point2 color {:thickness :line-type :tip-length}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod arrowed-line :java-mat
  [^Mat src {x1 :x y1 :y} {x2 :x y2 :y} color &
   {:keys [thickness line-type tip-length]
    :or {thickness 1 line-type LINE_8 tip-length 0.1}}]
  (Imgproc/arrowedLine src (Point. x1 y1) (Point. x2 y2) (java-scalar color) thickness
                       line-type 0 tip-length)
  src)

(defmethod-py arrowed-line :ndarray
  [src {x1 :x y1 :y} {x2 :x y2 :y} color &
   {:keys [thickness line-type tip-length]
    :or {thickness 1 line-type LINE_8 tip-length 0.1}}]
  (cv2/arrowedLine src [x1 y1] [x2 y2] (python-scalar color) thickness line-type 0
                   tip-length))


(defmulti bitwise-and
  "Based on Core/bitwise_and
   -----------------------------------------------------------------------
   Performs a per-element bitwise conjunction of two matrices. Input is
   [src1 src2 {:dst :mask}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod bitwise-and :java-mat
  [src1 src2 & {:keys [dst mask]}]
  (let [dst (or dst (Mat.))]
    (Core/bitwise_and src1 src2 dst (or mask (Mat.)))
    dst))

(defmethod-py bitwise-and :ndarray
  [src1 src2 & {:keys [dst mask]}]
  (cv2/bitwise_and src1 src2 dst mask))

(defmethod-cuda bitwise-and :cuda-gpu-mat
  [src1 src2 & {:keys [dst mask]}]
  (cuda/bitwise_and src1 src2 dst mask))

(defn bitwise-and!
  "Variant of bitwise-and in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply bitwise-and src1 (concat rest [:dst src1])))


(defmulti bitwise-not
  "Based on Core/bitwise_not
   -----------------------------------------------------------------------
   Inverts every bit of an array. Input is
   [src {:dst :mask}]"
  (fn [src & rest] (mat-type src)))

(defmethod bitwise-not :java-mat
  [src & {:keys [dst mask]}]
  (let [dst (or dst (Mat.))]
    (Core/bitwise_not src dst (or mask (Mat.)))
    dst))

(defmethod-py bitwise-not :ndarray
  [src & {:keys [dst mask]}]
  (cv2/bitwise_not src dst mask))

(defmethod-cuda bitwise-not :cuda-gpu-mat
  [src & {:keys [dst mask]}]
  (cuda/bitwise_not src dst mask))

(defn bitwise-not!
  "Variant of bitwise-not in which src is also the destination matrix dst."
  [src & rest]
  (apply bitwise-not src (concat rest [:dst src])))


(defmulti bitwise-or
  "Based on Core/bitwise_or
   -----------------------------------------------------------------------
   Performs a per-element bitwise disjunction of two matrices. Input is
   [src1 src2 {:dst :mask}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod bitwise-or :java-mat
  [src1 src2 & {:keys [dst mask]}]
  (let [dst (or dst (Mat.))]
    (if mask
      (Core/bitwise_or src1 src2 dst mask)
      (Core/bitwise_or src1 src2 dst))
    dst))

(defmethod-py bitwise-or :ndarray
  [src1 src2 & {:keys [dst mask]}]
  (cv2/bitwise_or src1 src2 dst mask))

(defmethod-cuda bitwise-or :cuda-gpu-mat
  [src1 src2 & {:keys [dst mask]}]
  (cuda/bitwise_or src1 src2 dst mask))

(defn bitwise-or!
  "Variant of bitwise-or in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply bitwise-or src1 (concat rest [:dst src1])))


(defmulti bitwise-xor
  "Based on Core/bitwise_xor
   -----------------------------------------------------------------------
   Performs a per-element bitwise exclusive or of two matrices. Input is
   [src1 src2 {:dst :mask}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod bitwise-xor :java-mat
  [src1 src2 & {:keys [dst mask]}]
  (let [dst (or dst (Mat.))]
    (if mask
      (Core/bitwise_xor src1 src2 dst mask)
      (Core/bitwise_xor src1 src2 dst))
    dst))

(defmethod-py bitwise-xor :ndarray
  [src1 src2 & {:keys [dst mask]}]
  (cv2/bitwise_xor src1 src2 dst mask))

(defmethod-cuda bitwise-xor :cuda-gpu-mat
  [src1 src2 & {:keys [dst mask]}]
  (cuda/bitwise_xor src1 src2 dst mask))

(defn bitwise-xor!
  "Variant of bitwise-xor in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply bitwise-xor src1 (concat rest [:dst src1])))


(defmulti calc-hist
  "Based on Imgproc/calcHist (see also calc-hist-2d)
   -----------------------------------------------------------------------
   Calculates a histogram for a matrix. The values in the matrix should range
   from min-value (inclusive) to max-value (exclusive). Input is
   [src bin-count [min-value max-value] {:mask :dst}]

   Output is a size [1 bin-count] float matrix.

   NOTE: Currently not defined for gpu mats."
  (fn [src & rest] (mat-type src)))

(defmethod calc-hist :java-mat
  [^Mat src bin-count range & {:keys [mask dst]}]
  (let [dst (or dst (Mat.))
        mask (or mask (Mat.))]
    (Imgproc/calcHist (ArrayList. [src])
                      (MatOfInt. (into-array Integer/TYPE [0]))
                      mask dst
                      (MatOfInt. (into-array Integer/TYPE [bin-count]))
                      (MatOfFloat. (into-array Float/TYPE range)))
    dst))

(defmethod-py calc-hist :ndarray
  [src bin-count range & {:keys [mask dst]}]
  (cv2/calcHist [src] [0] mask [bin-count] range dst))


(defmulti calc-hist-2d
  "Based on Imgproc/calcHist
   -----------------------------------------------------------------------
   Calculates a 2D histogram for two single-channel matrices. If the first matrix
   has multiple channels, then instead its first two channels will be used and
   the second matrix will be ignored. The values each matrix should range from
   min-value (inclusive) to max-value (exclusive). Input is
   [src1 bin-count1 [min-value1 max-value1] src2 bin-count2 [min-value2 max-value2]
    {:mask :dst}]

   Output is a size [bin-count2 bin-count1] float matrix.

   NOTE: Currently not defined for gpu mats."
  (fn [src1 & rest] (mat-type src1)))

(defmethod calc-hist-2d :java-mat
  [^Mat src1 bin-count1 range1 ^Mat src2 bin-count2 range2 & {:keys [mask dst]}]
  (let [dst (or dst (Mat.))
        mask (or mask (Mat.))]
    (Imgproc/calcHist (ArrayList. [src1 src2])
                      (MatOfInt. (into-array Integer/TYPE [0 1]))
                      mask dst
                      (MatOfInt. (into-array Integer/TYPE [bin-count1 bin-count2]))
                      (MatOfFloat. (into-array Float/TYPE (concat range1 range2))))
    dst))

(defmethod-py calc-hist-2d :ndarray
  [src1 bin-count1 range1 src2 bin-count2 range2 & {:keys [mask dst]}]
  (cv2/calcHist [src1 src2] [0 1] mask [bin-count1 bin-count2]
                (vec (concat range1 range2)) dst))


(defmulti canny
  "Based on Imgproc/Canny
   -----------------------------------------------------------------------
   Finds edges in an image using the Canny algorithm. The user must specify the
   first and second thresholds for the hysteresis procedure. Optionally, they can
   specify an apertureSize for the Sobel operator and whether an L2gradient
   should be used (by default it isn't). They should specify apertureSize if they
   also want to specify that L2gradient is true. Input is
   [src threshold1 threshold2 {:aptertureSize :L2gradient :dst}]

   NOTE: The gpu-mat version of this method only works on single-channel matrices."
  (fn [src & rest] (mat-type src)))

(defmethod canny :java-mat
  [^Mat src threshold1 threshold2 & {:keys [apertureSize L2gradient dst]
                                     :or {L2gradient false}}]
  (let [dst (or dst (Mat.))]
    (if apertureSize
      (Imgproc/Canny src dst threshold1 threshold2 apertureSize L2gradient)
      (Imgproc/Canny src dst threshold1 threshold2))
    dst))

(defmethod-py canny :ndarray
  [src threshold1 threshold2 & {:keys [apertureSize L2gradient dst]
                                :or {L2gradient false}}]
  (cv2/Canny src threshold1 threshold2 dst apertureSize L2gradient))

(defmethod-cuda canny :cuda-gpu-mat
  [src threshold1 threshold2 & {:keys [apertureSize L2gradient dst]
                                :or {L2gradient false}}]
  (let [detector (cuda/createCannyEdgeDetector threshold1 threshold2
                                               apertureSize L2gradient)
        dst (or dst (cv2/cuda_GpuMat (gpu-size src) (py/$a src type)))]
    (py/$a detector detect src dst)
    dst))

(defn canny!
  "Variant of canny in which src is also the destination matrix dst."
  [src & rest]
  (apply canny src (concat rest [:dst src])))


(defmulti cart-to-polar
  "Based on Core/cartToPolar
   -----------------------------------------------------------------------
   Takes two matrices, one describing x-values and the other
   describing y-values (for example, vertical and horizontal motion). Converts these
   to polar coordinates and writes them to new matrices that store the angle
   and magnitude. Angle is in degrees if :degrees? is true (it's true by default).
   Input is
   [src-x src-y {:angle :magnitude :degrees?}]
   Output is [angle magnitude]."
  (fn [src-x & rest] (mat-type src-x)))

(defmethod cart-to-polar :java-mat
  [^Mat src-x ^Mat src-y & {:keys [angle magnitude degrees?] :or {degrees? true}}]
  (let [angle (or angle (Mat.))
        magnitude (or magnitude (Mat.))]
    (Core/cartToPolar src-x src-y magnitude angle degrees?)
    [angle magnitude]))

(defmethod-py cart-to-polar :ndarray
  [src-x src-y & {:keys [angle magnitude degrees?] :or {degrees? true}}]
  (let [[magnitude angle] (cv2/cartToPolar src-x src-y magnitude angle degrees?)]
    [angle magnitude]))

(defmethod-cuda cart-to-polar :cuda-gpu-mat
  [src-x src-y & {:keys [angle magnitude degrees?] :or {degrees? true}}]
  (let [[magnitude angle] (cuda/cartToPolar src-x src-y magnitude angle degrees?)]
    [angle magnitude]))


(defmulti channels "Returns the number of channels for a matrix."
  (fn [src] (general-mat-type src)))

(defmethod channels :java-mat [src]
  (.channels src))

(defmethod-py channels :ndarray [src]
  (if (< (py/get-attr src :ndim) 3)
    1
    (nth (np-shape src) 2)))

(defmethod-cuda channels :cuda-gpu-mat [src]
      (py/$a src channels))


(defmulti circle
  "Based on Imgproc/circle
   -----------------------------------------------------------------------
   Draws a circle onto a matrix and returns the matrix. thickness is an integer and
   defaults to 1. It can also be the constant FILLED for a filled shape.
   line-type defaults to LINE_8 (use LINE_AA for antialiased).
   Points are in the form {:x x :y y}. Color can be a single number or a vector
   of numbers, one for each channel. Input is
   [src center-point radius color {:thickness :line-type}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod circle :java-mat
  [^Mat src {x :x y :y} radius color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (Imgproc/circle src (Point. x y) radius (java-scalar color) thickness line-type)
  src)

(defmethod-py circle :ndarray
  [src {x :x y :y} radius color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (cv2/circle src [x y] radius (python-scalar color) thickness line-type))


(defmulti compare-hist
  "Based on Imgproc/compareHist
   -----------------------------------------------------------------------
   Compares two histograms, according to the specified method (default is
   COMP_CORREL). Input is
   [src1 src2 {:method}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod compare-hist :java-mat
  [^Mat src1 ^Mat src2 & {:keys [method] :or {method COMP_CORREL}}]
  (Imgproc/compareHist src1 src2 method))

(defmethod-py compare-hist :ndarray
  [src1 src2 & {:keys [method] :or {method COMP_CORREL}}]
  (cv2/compareHist src1 src2 method))


(defmulti connected-components-with-stats
  "Based on Imgproc/connectedComponentsWithStats
   -----------------------------------------------------------------------
   Finds connected non-zero regions in src. If connectivity is specified, it can
   be 4 (4-way connectivity, the default) or 8 (8-way connectivity). Input is
   [src {:connectivity}]
   Output is
   [labels stats centroids]
   where labels is a 32S image of the same size as src with a label assigned to each
   point, stats is a label-indexed sequence of items of the form
   {:label label :region region :area area}, and centroids is a label-indexed
   sequence of the form {:label label :centroid [x y]}.

   NOTE: Not currently implemented for gpu mats."
  (fn [src & rest] (mat-type src)))

(defmethod connected-components-with-stats :java-mat
  [^Mat src & {:keys [connectivity] :or {connectivity 4}}]
  (let [labels (Mat.)
        stats (Mat.)
        centroids (Mat.)]
    (Imgproc/connectedComponentsWithStats src labels stats centroids connectivity
                                          CV_32S)
    [labels
    (map (fn [label]
           {:label label
            :region {:x (poll-pixel stats Imgproc/CC_STAT_LEFT label CV_32S 1)
                     :y (poll-pixel stats Imgproc/CC_STAT_TOP label CV_32S 1)
                     :width (poll-pixel stats Imgproc/CC_STAT_WIDTH label CV_32S 1)
                     :height (poll-pixel stats Imgproc/CC_STAT_HEIGHT label CV_32S 1)}
            :area (poll-pixel stats Imgproc/CC_STAT_AREA label CV_32S 1)})
         (range (.height stats)))
    (map (fn [label]
           {:label label
            :centroid [(poll-pixel centroids 0 label CV_64F 1)
                       (poll-pixel centroids 1 label CV_64F 1)]})
         (range (.height centroids)))]))

(defmethod-py connected-components-with-stats :ndarray
  [src & {:keys [connectivity ltype] :or {connectivity 4 ltype CV_32S}}]
  (let [[labels stats centroids]
        (-> (cv2/connectedComponentsWithStats src nil nil nil connectivity ltype)
            vec rest)
        label-seq (-> (py/get-attr stats :shape) vec first range)]
    [labels
     (map (fn [label]
            {:label label
             :region {:x (py/get-item stats [label cv2/CC_STAT_LEFT])
                      :y (py/get-item stats [label cv2/CC_STAT_TOP])
                      :width (py/get-item stats [label cv2/CC_STAT_WIDTH])
                      :height (py/get-item stats [label cv2/CC_STAT_HEIGHT])}
             :area (py/get-item stats [label cv2/CC_STAT_AREA])})
          label-seq)
     (map (fn [label]
            {:label label
             :centroid [(py/get-item centroids [label 0])
                        (py/get-item centroids [label 1])]})
          label-seq)]))


(defmulti convert-to
  "Based on .convertTo (Applies to typical mats; for mats of points such as contours,
   use convert-points-to.)
   -----------------------------------------------------------------------
   Converts a matrix to a particular data type. Note that the number of channels
   will not be changed (use cvt-color or split to change the number of channels).
   Optionally also multiplies all values in the matrix by a number alpha and
   adds a number beta (in that order) before converting.
   Input is
   [src dst-type {:alpha :beta :dst}]"
  (fn [src & rest] (general-mat-type src)))

(defmethod convert-to :java-mat
  [^Mat src dst-type & {:keys [alpha beta dst]}]
  (let [dst (or dst (dst-for-java-mat src dst-type))]
    (cond
      beta (.convertTo src dst dst-type (or alpha 1) beta)
      alpha (.convertTo src dst dst-type alpha)
      :else (.convertTo src dst dst-type))
    dst))

(defmethod-py convert-to :ndarray
  [src dst-type & {:keys [alpha beta dst] :or {alpha 1 beta 0}}]
  (let [dtype (type->dtype dst-type)]
    (if (= dtype "uint8")
      (cv2/convertScaleAbs src dst alpha beta)
      (-> (py/$a src astype dtype)
          (cv2/multiply (python-scalar alpha) dst)
          (cv2/add (python-scalar beta) dst)))))

(defmethod-cuda convert-to :cuda-gpu-mat
  [src dst-type & {:keys [alpha beta dst] :or {alpha 1 beta 0}}]
  (let [dst (or dst (cv2/cuda_GpuMat (gpu-size src) dst-type))]
    (py/$a src convertTo dst-type alpha dst beta)))

(defn convert-to!
  "Variant of convert-to in which src is also the destination matrix dst (usually
   not useful because changing the depth will necessitate allocating a new matrix
   anyway)."
  [src & rest]
  (apply convert-to src (concat rest [:dst src])))


(defmulti copy
  "Based on .copyTo and .clone
   -----------------------------------------------------------------------
   Copy a src matrix, potentially using a mask to specify which particular elements
   should be copied. If no dst matrix is specified, one will be created, and any
   elements not covered by a mask will be set to 0. Input is
   [src {:dst :mask}]"
  (fn [src & rest] (general-mat-type src)))

(defmethod copy :java-mat
  [^Mat src & {:keys [mask dst]}]
   (let [^Mat dst (or dst (Mat.))
         ^Mat mask (or mask (Mat.))]
     (.copyTo src dst mask)
     dst))

(defmethod-py copy :ndarray
  [src & {:keys [mask dst]}]
  (cv2/copyTo src mask dst))

(defmethod-cuda copy :cuda-gpu-mat
  [src & {:keys [mask dst]}]
  (let [dst (or dst (cv2/cuda_GpuMat (py/$a src size) (py/$a src type)))]
    (py/$a src copyTo dst mask)))


(defmulti copy-make-border
  "Based on Core/copyMakeBorder
   -----------------------------------------------------------------------
   Copies the src matrix into a larger matrix with a border added around it.
   top, bottom, left, and right indicate the width of the border on each side.
   border-type indicates how to create the border (e.g., BORDER_REPLICATE). value,
   if provided, is a constant (number or vector) that will be used throughout the
   border if border-type is BORDER_CONSTANT. Input is
   [src top bottom left right border-type {:dst :value}]"
  (fn [src & rest] (mat-type src)))

(defmethod copy-make-border :java-mat
  [^Mat src top bottom left right border-type & {:keys [value dst]}]
   (let [^Mat dst (or dst (Mat.))]
     (if value
       (Core/copyMakeBorder src dst top bottom left right border-type
                            (java-scalar value))
       (Core/copyMakeBorder src dst top bottom left right border-type))
     dst))

(defmethod-py copy-make-border :ndarray
  [src top bottom left right border-type & {:keys [value dst]}]
  (cv2/copyMakeBorder src top bottom left right border-type dst (python-scalar value)))

(defmethod-cuda copy-make-border :cuda-gpu-mat
  [src top bottom left right border-type & {:keys [value dst]}]
  (cuda/copyMakeBorder src top bottom left right border-type dst (python-scalar value)))


(defmulti count-non-zero
  "Based on Core/countNonZero
   -----------------------------------------------------------------------
   Returns the number of non-zero elements in a single-channel matrix. Input is
   [src]"
  (fn [src] (mat-type src)))

(defmethod count-non-zero :java-mat
  [^Mat src]
  (Core/countNonZero src))

(defmethod-py count-non-zero :ndarray
  [src]
  (cv2/countNonZero src))

(defmethod-cuda count-non-zero :cuda-gpu-mat
  [src]
  (cuda/countNonZero src))


(defmulti create-lookup-table
  "Used together with apply-lookup-table to support Core/LUT
   -----------------------------------------------------------------------
   Creates a look up table for using with apply-lookup-table. By default, the table
   will be applied to 8U images. Alternatively, you can provie a max-input, which
   will be used to convert an image to 8U. For example, if max-input is 1, then an
   image will be converted to 8U and scaled such that 1 maps to 255 before applying
   the table.
   optional-ref-mat, which can be any matrix, serves as a cue for what type of
   table to make, e.g., java-mat vs. numpy array. Or, you can skip this argument
   and a java-mat will be made by default.
   data is a java array (its datatype will become the datatype for the lookup table,
   unless we we are working with gpu images)
   If G and R are provided, then they will be used along with table to make a
   three-channel, BGR lookup table.
   If table-type is provided, the lookup table will be converted to this type
   (unless this is a gpu lookup table).
   If max-output is provided, it will be used only for gpu lookup tables which are
   8U only to convert the output of the lookup table to a float, where 255 in the
   lookup table maps to max-output. Input is
   [optional-ref-mat data {:max-input :max-output :G :R}]"
  (fn [optional-ref-mat & rest] (mat-type optional-ref-mat)))

(defmethod create-lookup-table :java-mat
  [_ data & {:keys [max-input max-output G R table-type]}]
  (let [depth (-> data class (.getComponentType) jtype->depth)
        table (Mat. 256 1 depth)]
    (.put table 0 0 data)

    (when (and G R)
      (let [tableG (Mat. 256 1 depth)
            tableR (Mat. 256 1 depth)
            channels (java.util.ArrayList.)]
        (.put tableG 0 0 G)
        (.put tableR 0 0 R)
        (.add channels table)
        (.add channels tableG)
        (.add channels tableR)
        (Core/merge channels table)))

    (when table-type
      (.convertTo table table table-type))

    {:max-input max-input :table table}))

(defmethod-py create-lookup-table :ndarray
  [_ data & {:keys [max-input max-output G R table-type]}]
  {:max-input max-input
   :table
   (cond-> (np/array data)

           (and G R)
           (-> (vector (np/array G) (np/array R)) cv2/merge)

           table-type
           (convert-to table-type))})

(defmethod-cuda create-lookup-table :cuda-gpu-mat
  [_ data & {:keys [max-input max-output G R table-type]}]
  {:max-input max-input :max-output max-output
   :channels (or (and G R 3) 1)
   :table
   (-> (if (and G R)
         (cv2/merge [(np/array data) (np/array G) (np/array R)])
         (np/array data))
       cv2/transpose
       (cond-> max-output (cv2/multiply (python-scalar (/ 255.0 max-output))))
       cv2/convertScaleAbs cuda/createLookUpTable)})

(defmethod create-lookup-table :default
  [& args]
  (apply create-lookup-table (new-java-mat [1 1] CV_8UC1) args))


(defmulti cvt-color
  "Based on Imgproc/cvtColor
   -----------------------------------------------------------------------
   Convert a matrix's color to a different format. Examples of
   code include COLOR_BGR2GRAY. Input is
   [src code {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod cvt-color :java-mat
  [^Mat src code & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Imgproc/cvtColor src dst code)
    dst))

(defmethod-py cvt-color :ndarray
  [src code & {:keys [dst]}]
  (cv2/cvtColor src code dst))

(defmethod-cuda cvt-color :cuda-gpu-mat
  [src code & {:keys [dst]}]
  (cuda/cvtColor src code dst))

(defn cvt-color!
  "Variant of cvt-color in which src is also the destination matrix dst (mostly
   only useful if both color formats use the same number of channels)."
  [src & rest]
  (apply cvt-color src (concat rest [:dst src])))


(defmulti depth
  "Based on .depth
   -----------------------------------------------------------------------
   Returns a matrix's depth."
  (fn [src] (general-mat-type src)))

(defmethod depth :java-mat
  [src]
  (.depth src))

(defmethod-py depth :ndarray
  [src]
  (-> (py/get-attr src :dtype) str dtype->depth))

(defmethod-cuda depth :cuda-gpu-mat
  [src]
  (py/$a src depth))


(defmulti depth-symbol
  "Returns a symbol representing a matrix's depth."
  (fn [src] (general-mat-type src)))

(defmethod depth-symbol :java-mat
  [src]
  (depth->symbol (.depth src)))

(defmethod-py depth-symbol :ndarray
  [src]
  (-> (py/get-attr src :dtype) str dtype->depth depth->symbol))

(defmethod-cuda depth-symbol :cuda-gpu-mat
  [src]
  (depth->symbol (py/$a src depth)))


(defmulti dot
  "Based on the .dot method for Mats
   -----------------------------------------------------------------------
   Returns the dot product of two matrices. Input is
   [src1 src2]

   NOTE: Not currently implemented for gpu mats."
  (fn [src1 & rest] (mat-type src1)))

(defmethod dot :java-mat
  [^Mat src1 ^Mat src2]
  (.dot src1 src2))

(defmethod-py dot :ndarray
  [src1 src2]
  (np/dot src1 src2))


(defmulti dilate
  "Based on Imgproc/dilate
   -----------------------------------------------------------------------
   Performs an dilation morphological transformation on the source matrix, using
   a kernel with the specified [k-width k-height] size.
   Input is
   [src [k-width k-height] {:dst :iterations :border-type :border-value}]

   NOTE: border-type and border-value are not used for gpu matrices."
  (fn [src & rest] (mat-type src)))

(defmethod dilate :java-mat
  [^Mat src [k-width k-height] & {:keys [dst iterations border-type border-value]
                                  :or {iterations 1 border-type BORDER_DEFAULT
                                       border-value (Scalar. 0 0 0 0)}}]
  (let [dst (or dst (Mat.))]
    (Imgproc/dilate
     src dst
     (Imgproc/getStructuringElement Imgproc/MORPH_RECT (Size. k-width k-height))
     (Point. -1 -1) iterations border-type border-value)
    dst))

(defmethod-py dilate :ndarray
  [src size & {:keys [dst iterations border-type border-value]
               :or {iterations 1 border-type BORDER_DEFAULT
                    border-value (Scalar. 0 0 0 0)}}]
  (cv2/dilate src (np/ones size np/uint8) dst -1 iterations border-type
             border-value))

(defmethod-cuda dilate :cuda-gpu-mat
  [src size & {:keys [dst iterations border-type border-value]
                  :or {iterations 1}}]
  (let [f (cuda/createMorphologyFilter
           MORPH_DILATE (py/$a src type) (np/ones size np/uint8) nil iterations)
        dst (or dst (new-mat src))]
    (py/$a f apply src dst)
    dst))

(defn dilate!
  "Variant of dilate in which src is also the destination matrix dst."
  [src & rest]
  (apply dilate src (concat rest [:dst src])))


(defmulti divide
  "Based on Core/divide
   -----------------------------------------------------------------------
   Divide one matrix by another, or divide one matrix by a constant (src2), which
   can be either a number or a vector of numbers, one for each channel. Input is
   [src1 src2 {:dst}"
  (fn [src1 & rest] (mat-type src1)))

(defmethod divide :java-mat
  [^Mat src1 src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/divide src1 (java-scalar src2) dst)
    dst))

(defmethod-py divide :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/divide src1 (python-scalar src2) dst))

;;Dividing a cuda-gpu-mat by a scalar is currently unsupported,
;;so we have to make a new cuda-gpu-mat containing the scalar value.
(defmethod-cuda divide :cuda-gpu-mat
  [src1 src2 & {:keys [dst]}]
   (if (or (number? src2) (vector? src2))
     (cuda/divide
      src1
      (cv2/cuda_GpuMat (gpu-size src1) (py/$a src1 type) (python-scalar src2))
      dst)
     (cuda/divide src1 src2 dst)))

(defn divide!
  "Variant of divide in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply divide src1 (concat rest [:dst src1])))


(defmulti erode
  "Based on Imgproc/erode
   -----------------------------------------------------------------------
   Performs an erosion morphological transformation on the source matrix, using
   a kernel with the specified [k-width k-height] size.
   Input is
   [src [k-width k-height] {:dst :iterations :border-type :border-value}]

   NOTE: border-type and border-value are not used for gpu matrices."
  (fn [src & rest] (mat-type src)))

(defmethod erode :java-mat
  [^Mat src [k-width k-height] & {:keys [dst iterations border-type border-value]
                                  :or {iterations 1 border-type BORDER_DEFAULT
                                       border-value (Scalar. 0 0 0 0)}}]
  (let [dst (or dst (Mat.))]
    (Imgproc/erode
     src dst
     (Imgproc/getStructuringElement Imgproc/MORPH_RECT (Size. k-width k-height))
     (Point. -1 -1) iterations border-type border-value)
    dst))

(defmethod-py erode :ndarray
  [src size & {:keys [dst iterations border-type border-value]
               :or {iterations 1 border-type BORDER_DEFAULT
                    border-value (Scalar. 0 0 0 0)}}]
  (cv2/erode src (np/ones size np/uint8) dst -1 iterations border-type
             border-value))

(defmethod-cuda erode :cuda-gpu-mat
  [src size & {:keys [dst iterations border-type border-value]
                  :or {iterations 1}}]
  (let [f (cuda/createMorphologyFilter
           MORPH_ERODE (py/$a src type) (np/ones size np/uint8) nil iterations)
        dst (or dst (new-mat src))]
    (py/$a f apply src dst)
    dst))

(defn erode!
  "Variant of erode in which src is also the destination matrix dst."
  [src & rest]
  (apply erode src (concat rest [:dst src])))


(defmulti exp
  "Based on Core/exp
   -----------------------------------------------------------------------
   Computes the exponent of element in the src matrix. Input is
   [src power {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod exp :java-mat
  [^Mat src & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/exp src dst)
    dst))

(defmethod-py exp :ndarray
  [src & {:keys [dst]}]
  (cv2/exp src dst))

(defmethod-cuda exp :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/exp src dst))

(defn exp!
  "Variant of exp in which src is also the destination matrix dst."
  [src & rest]
  (apply exp src (concat rest [:dst src])))


(defmulti extract-channel
  "Based on Core/extractChannel
   -----------------------------------------------------------------------
   Extract a (zero-indexed) channel from a src matrix. Input is
   [src channel {:dst}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod extract-channel :java-mat
  [^Mat src channel & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/extractChannel src dst channel)
    dst))

(defmethod-py extract-channel :ndarray
  [src channel & {:keys [dst]}]
  (cv2/extractChannel src channel dst))


(defmulti filter-2D
  "Based on Imgproc/filter2D
   -----------------------------------------------------------------------
   Convolves a 2D kernel, created for example with get-gabor-kernel, across a
   matrix. If dst is not provided dst-type can indicate the desired depth of the
   destination matrix dst (default is to match the src). delta is a number that
   will be added to all filtered values in dst. border-type indicates
   how to extrapolate information at the edge of the image. Input is
   [src kernel {:dst :dst-type :delta :border-type}]"
  (fn [src & rest] (mat-type src)))

(defmethod filter-2D :java-mat
  [^Mat src ^Mat kernel & {:keys [dst dst-type delta border-type]
                           :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (let [dst (or dst (new-mat src :depth (when (>= dst-type 0) dst-type)))]
    (Imgproc/filter2D src dst dst-type kernel (Point. -1 -1) delta border-type)
    dst))

(defmethod-py filter-2D :ndarray
  [src kernel & {:keys [dst dst-type delta border-type]
                           :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (cv2/filter2D src dst-type kernel dst nil delta border-type))

(defmethod-cuda filter-2D :cuda-gpu-mat
  [src kernel & {:keys [dst dst-type delta border-type]
                           :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (let [kernel (->numpy kernel)
        src-type (py/$a src type)
        dst-type (if (>= dst-type 0)
                    dst-type
                    src-type)
        f (cuda/createLinearFilter src-type dst-type
                                   kernel nil border-type)
        dst (or dst (new-mat src :depth (when (>= dst-type 0) dst-type)))]
    (py/$a f apply src dst)
    dst))

(defn filter-2D!
  "Variant of filter-2D in which src is also the destination matrix dst."
  [src & rest]
  (apply filter-2D src (concat rest [:dst src])))


(declare in-range)
(defmulti find-segments-by-connection
  "Based on Imgproc/connectedComponentsWithStats
   -----------------------------------------------------------------------
   Finds a list of segments in an image based on connected regions. If connectivity
   is specified, it can be 4 (4-way connectivity, the default) or 8 (8-way
   connectivity). Returns a list of segments of the form {:region region
   :mask mask :area area). Input is
   [src {:connectivity}]

   NOTE: Not currently implemented for gpu mats."
  (fn [src & rest] (mat-type src)))

(defmethod find-segments-by-connection :java-mat
  [^Mat src & {:keys [connectivity] :or {connectivity 4}}]
  (let [labels (Mat.)
        stats (Mat.)
        centroids (Mat.)]
    (Imgproc/connectedComponentsWithStats src labels stats centroids connectivity
                                          CV_32S)
    (map (fn [label]
           (let [region
                 {:x (poll-pixel stats Imgproc/CC_STAT_LEFT label CV_32S 1)
                  :y (poll-pixel stats Imgproc/CC_STAT_TOP label CV_32S 1)
                  :width (poll-pixel stats Imgproc/CC_STAT_WIDTH label CV_32S 1)
                  :height (poll-pixel stats Imgproc/CC_STAT_HEIGHT label CV_32S 1)}]
             {:region region
              :mask (in-range (submat labels region) label label)
              :area (poll-pixel stats Imgproc/CC_STAT_AREA label CV_32S 1)}))
         (rest (range (.height stats))))))

(defmethod-py find-segments-by-connection :ndarray
  [src & {:keys [connectivity] :or {connectivity 4}}]
  (let [[labels stats centroids]
        (-> (cv2/connectedComponentsWithStats src nil nil nil connectivity CV_32S)
            vec rest)]
    (map (fn [label]
           (let [region
                 {:x (py/get-item stats [label cv2/CC_STAT_LEFT])
                  :y (py/get-item stats [label cv2/CC_STAT_TOP])
                  :width (py/get-item stats [label cv2/CC_STAT_WIDTH])
                  :height (py/get-item stats [label cv2/CC_STAT_HEIGHT])}]
             {:region region
              :mask (in-range (submat labels region) label label)
              :area (py/get-item stats [label cv2/CC_STAT_AREA])}))
         (-> (py/get-attr stats :shape) vec first range rest))))


(defmulti find-segments-by-contour
  "Based on Imgproc/findContours
   -----------------------------------------------------------------------
   Finds a list of segments in an image based on closed contours. Returns a list of
   segments of the form {:region region :mask mask :area area :subsegments subsegments), where
   subsegments will be non-nil only if mode is RETR_TREE or RETR_CCOMP. If
   :convex? is true, all contours are converted to their convex hulls. Input is
   [src mode {:convex?}]

   NOTE: Not currently implemented for gpu mats."
  (fn [src & rest] (mat-type src)))

(defmethod find-segments-by-contour :java-mat
  [^Mat src mode & {:keys [convex?]}]
  (let [contours (ArrayList.)
        hierarchy (Mat.)]
    (Imgproc/findContours src contours hierarchy mode Imgproc/CHAIN_APPROX_SIMPLE)
    (-> contours
        (cond->> convex? (map contour-convex-hull))
        vec
        (contours->segments hierarchy src))))

(defmethod-py find-segments-by-contour :ndarray
  [src mode & {:keys [convex?]}] ;;<----convex? untested
  (let [[contours hierarchy]
        (vec (cv2/findContours src mode Imgproc/CHAIN_APPROX_SIMPLE))]
    (-> contours
        (cond->> convex? (map #(cv2/convexHull % nil true true)))
        vec
        (contours->segments hierarchy src))))

(defmulti flip
  "Based on Core/flip
   -----------------------------------------------------------------------
   Flip a matrix over the x-axis (if flipCode is 0), the y-axis (if flipCode is
   positive), or both (if flipCode is negative). Input is
   [src flipCode {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod flip :java-mat
  [^Mat src flipCode & {:keys [dst]}]
   (let [dst (or dst (Mat.))]
     (Core/flip src dst flipCode)
     dst))

(defmethod-py flip :ndarray
  [src flipCode & {:keys [dst]}]
  (cv2/flip src flipCode dst))

(defmethod-cuda flip :cuda-gpu-mat
  [src flipCode & {:keys [dst]}]
  (cuda/flip src flipCode dst))

(defn flip!
  "Variant of flip in which src is also the destination matrix dst."
  [src & rest]
  (apply flip src (concat rest [:dst src])))


(defmulti gaussian-blur
  "Based on Imgproc/GaussianBlur
   -----------------------------------------------------------------------
   Blurs a matrix by convolving it with a Gaussian. Kernel size is a [width
   height] vector, where width and height should be odd. The derivative of the
   Gaussian is determined automatically, unless sigma is provided. Input is
   [src ksize {:border-type :dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod gaussian-blur :java-mat
  [^Mat src [kwidth kheight] & {:keys [border-type dst sigma]
                                :or {border-type BORDER_DEFAULT sigma 0}}]
   (let [dst (or dst (Mat.))]
     (Imgproc/GaussianBlur src dst (Size. kwidth kheight) sigma sigma border-type)
     dst))

(defmethod-py gaussian-blur :ndarray
  [src ksize & {:keys [border-type dst sigma]
                :or {border-type BORDER_DEFAULT sigma 0}}]
  (cv2/GaussianBlur src ksize 0 dst 0 border-type))

(defmethod-cuda gaussian-blur :cuda-gpu-mat
  [src ksize & {:keys [border-type dst sigma]
                :or {border-type BORDER_DEFAULT sigma 0}}]
  (let [dtype (py/$a src type)
        ;dst (or dst (cv2/cuda_GpuMat (gpu-size src) dtype))
        dst (or dst (new-mat src))
        gfilter
        (cuda/createGaussianFilter dtype dtype ksize sigma sigma
                                   border-type border-type)]
    (py/$a gfilter apply src dst)
    dst))

(defn gaussian-blur!
  "Variant of gaussian-blur in which src is also the destination matrix dst."
  [src & rest]
  (apply gaussian-blur src (concat rest [:dst src])))


(defmulti gemm
  "Based on Core/gemm
   -----------------------------------------------------------------------
   Performs a generalized matrix multiplication operation. At a minimum, performs
   src1 * src2, where (= (width src1) (height src2)). If alpha is provided, it
   is a number that is multiplied over src1. If src3 is provided, it is added to
   the result of the multiplification. If beta is provided, it is a number that
   is multiplied over src3. Input is
   [src1 src2 {:dst :alpha :src3 :beta}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod gemm :java-mat
  [^Mat src1 ^Mat src2 & {:keys [dst alpha src3 beta]
                           :or {alpha 1.0 beta 1.0 src3 (Mat.)}}]
  (let [dst (or dst (Mat.))]
    (Core/gemm src1 src2 alpha src3 beta dst)
    dst))

(defmethod-py gemm :ndarray
  [src1 src2 & {:keys [dst alpha src3 beta] :or {alpha 1.0 beta 1.0}}]
  (cv2/gemm src1 src2 alpha src3 beta dst))

(defmethod-cuda gemm :cuda-gpu-mat ;;<-----Test
  [src1 src2 & {:keys [dst alpha src3 beta] :or {alpha 1.0 beta 1.0}}]
  (cuda/gemm src1 src2 alpha src3 beta dst))

(defn gemm!
  "Variant of gemm in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply gemm src1 (concat rest [:dst src1])))


(defmulti get
  "Based on Core/extractChannel
   -----------------------------------------------------------------------
   Extract a (zero-indexed) channel from a src matrix. Input is
   [src channel {:dst}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod get :java-mat
  [^Mat src channel & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/extractChannel src dst channel)
    dst))

(defmethod-py get :ndarray
  [src channel & {:keys [dst]}]
  (cv2/extractChannel src channel dst))


(defn *
  "Based on Core/gemm
   -----------------------------------------------------------------------
   Performs a matrix multiplication operation, src1 * src2 * src3 * ...
   For each multiplied pair src1 * src2, it should be the case that
   (= (width src1) (height src2)). Input is
   [src1 src2 ...]"
  [src1 & args]
  (if (seq args)
    (clojure.core/reduce gemm! (gemm src1 (first args)) (rest args))
    src1))


(defmulti get-gabor-kernel
  "Based on Imgproc/getGaborKernel
   -----------------------------------------------------------------------
   Returns a Gabor kernel which can be applied to an image with filter-2D.
   optional-ref-mat, which can be any matrix, serves as a cue for what type of
   kernel to make, e.g., java-mat vs. numpy array. Or, you can skip this argument
   and a java-mat will be made by default. The other inputs are as follows
   [width height]: size of the filter
   sigma: standard deviation of the Gaussian envelope
   theta: orientation of the normal to the parallel stripes of the Gabor function
   lambd: wavelength of the sinusoidal factor
   gamma: aspect ratio (default is 1)
   psi: phase offset (default is pi/2)
   dst-type: kernel will operate on matrices of this depth (default is 32F, other optins is 64F)
   Input is
   [optional-ref-mat [width height] sigma theta lambd {:gamma :psi :dst-type}]"
  (fn [optional-ref-mat & rest] (mat-type optional-ref-mat)))

(defmethod get-gabor-kernel :java-mat ;;<---test all
  [_ [width height] sigma theta lambd & {:keys [gamma psi dst-type]
                                         :or {gamma 1.0 psi (clojure.core/* Math/PI 0.5)
                                              dst-type CV_32F}}]
  (Imgproc/getGaborKernel (Size. width height) sigma theta lambd gamma psi dst-type))

(defmethod-py get-gabor-kernel :ndarray
  [_ [width height] sigma theta lambd & {:keys [gamma psi dst-type]
                                         :or {gamma 1.0 psi (clojure.core/* Math/PI 0.5)
                                              dst-type CV_32F}}]
  (cv2/getGaborKernel [height width] sigma theta lambd gamma psi dst-type))

(defmethod-cuda get-gabor-kernel :cuda-gpu-mat
  [_ & args]
  (apply get-gabor-kernel (new-numpy-array [2 2] CV_8UC1) args))

(defmethod get-gabor-kernel :default
  [& args]
  (apply get-gabor-kernel (new-java-mat [1 1] CV_8UC1) args))


(defmulti get-gaussian-kernel
  "Based on Imgproc/getGaussianKernel
   -----------------------------------------------------------------------
   Returns a 1-dimensional Gaussian kernel. Two such kernels can be applied to an
   image with sep-filter-2D (or you can use the higher-level function gaussian-blur).
   optional-ref-mat, which can be any matrix, serves as a cue for what type of
   kernel to make, e.g., jav a-mat vs. numpy array. Or, you can skip this argument
   and a java-mat will be made by default. The other inputs are as follows
   size: size of the filter
   sigma: standard deviation of the Gaussian (will be computed automatically, if
          it is not provided)
   dst-type: Determines depth of filter, either CV_32F or CV_64. Defaults to CV_32F.
   Input is
   [optional-ref-mat size {:dst-type :sigma}]"
  (fn [optional-ref-mat & rest] (mat-type optional-ref-mat)))

(defmethod get-gaussian-kernel :java-mat
  [_ size & {:keys [dst-type sigma] :or {dst-type CV_32F sigma 0}}]
  (Imgproc/getGaussianKernel size sigma dst-type))

(defmethod-py get-gaussian-kernel :ndarray ;;<---test this and gpu
  [_ size & {:keys [dst-type sigma] :or {dst-type CV_32F sigma 0}}]
  (cv2/getGaussianKernel size sigma dst-type))

(defmethod-cuda get-gaussian-kernel :cuda-gpu-mat
  [_ & args]
  (apply get-gaussian-kernel (new-numpy-array [2 2] CV_8UC1) args))

(defmethod get-gaussian-kernel :default
  [& args]
  (apply get-gaussian-kernel (new-java-mat [1 1] CV_8UC1) args))


(defmulti get-value
  "Based on .get
   -----------------------------------------------------------------------
   Returns the value at a specified x,y location in a matrix. Returns a single
   number for a single-channel matrix, or a vector of numbers for a multi-channel
   matrix.
   Input options are
   [src x y]
   [src {:x x :y y}]

   NOTE: Not currently implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod get-value :java-mat
  ([src x y]
  (poll-pixel src x y))
  ([src {x :x y :y}]
   (poll-pixel src x y)))

(defmethod-py get-value :ndarray
  ([src x y]
   (let [value (py/get-item src [y x])]
     (if (number? value)
       value
       (vec value))))
  ([src {x :x y :y}]
   (get-value src x y)))


(defmulti hconcat
  "Based on Core/hconcat
   -----------------------------------------------------------------------
   Takes one or more matrices, each of which should have the same number of
   rows, and concatenates them horizontally.  Input is
   [src-1 src-2 ...]

   NOTE: Not currently implemented for gpu mats."
  (fn [& sources] (mat-type (first sources))))

(defmethod hconcat :java-mat
  [& sources]
  (let [dst (Mat.)]
    (Core/hconcat (ArrayList. sources) dst)
    dst))

(defmethod-py hconcat :ndarray
  [& sources]
  (cv2/hconcat sources))


(defmulti kmeans
  "Based on Core/kmeans
   -----------------------------------------------------------------------
   Uses kmeans clustering to find clusters in a 1D or 2D matrix. Returns
   [dst compactness], where dst is a matrix the same size as src with cluster labels
   assigned to each point and compactness is the compactness score for kmeans
   clustering. :dst can also be passed as a keyword argument to seed the labels.
   If the keyword argument :return-centers? is true, then instead this function
   returns [dst compactness centers], where centers is a [1 k] matrix providing
   the center value of each label. Other arguments:

   * k: Number of clusters.
   * attempts: Run the algorithm this many times and pick the best results.
   * max-iterations: Compute at least this many iterations. Can be set to nil.
   * epsilon: Iterate at least until compactness reaches this value. Can be set to nil.
   * flags: Specifies how the centers are seeded. Default is KMEANS_RANDOM_CENTERS.
            If this is KMEANS_USE_INITIAL_LABELS, then use the labels found in dst.

   Input is
   [src k max-iterations epsilon attempts {:dst :flags :return-centers?}]

   NOTE: Not currently implemented for gpu mats or numpy arrays"
  (fn [src & rest] (mat-type src)))

(defmethod kmeans :java-mat
  [^Mat src k max-iterations epsilon attempts &
   {:keys [dst flags return-centers?] :or {flags KMEANS_RANDOM_CENTERS
                                           return-centers? false}}]
    (let [dst (or dst (Mat.))
          centers (and return-centers? (Mat.))
          term (TermCriteria. (+ (if max-iterations TermCriteria/MAX_ITER 0)
                                 (if epsilon TermCriteria/EPS 0))
                              (or max-iterations 0) (or epsilon 0))
          compactness (if return-centers?
                        (Core/kmeans src k dst term attempts flags centers)
                        (Core/kmeans src k dst term attempts flags))]
      (if return-centers?
        [dst compactness centers]
        [dst compactness])))



(defmulti in-range
  "Based on Core/inRange
   -----------------------------------------------------------------------
   Creates an 8U mask matrix with value 255 whenever values in src are between lowerb
   and upperb and with value 0 whenever they aren't. upperb and lowerb can be single
   values or vectors with one value per channel. Input is
   [src lowerb upperb {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod in-range :java-mat
  [^Mat src lowerb upperb & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/inRange src (java-scalar lowerb) (java-scalar upperb) dst)
    dst))

(defmethod-py in-range :ndarray
  [src lowerb upperb & {:keys [dst]}]
  (cv2/inRange src (python-scalar lowerb) (python-scalar upperb) dst))

(defmethod-cuda in-range :cuda-gpu-mat
  [src lowerb upperb & {:keys [dst]}]
  (cuda/inRange src (python-scalar lowerb) (python-scalar upperb) dst))

(defn in-range!
  "Variant of in-range in which src is also the destination matrix dst."
  [src & rest]
  (apply in-range src (concat rest [:dst src])))


(defmulti insert-channel
  "Based on Core/insertChannel
   -----------------------------------------------------------------------
   Sets one channel of dst to the values in the single-channel src.
   Note that, unlike in most functions, dst is the first argument. Returns
   dst. Input is
   [dst channel src]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod insert-channel :java-mat
  [^Mat dst channel ^Mat src]
  (Core/insertChannel src dst channel)
  dst)

(defmethod-py insert-channel :ndarray
  [dst channel src]
  (cv2/insertChannel src dst channel))


(defmulti line
  "Based on Imgproc/line
   -----------------------------------------------------------------------
   Draws a line onto a matrix and returns the matrix. thickness is an integer and
   defaults to 1. line-type defaults to LINE_8 (use LINE_AA for antialiased).
   Points are in the form {:x x :y y}. Color can be a single number or a vector
   of numbers, one for each channel. Input is
   [src point1 point2 color {:thickness :line-type}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod line :java-mat
  [^Mat src {x1 :x y1 :y} {x2 :x y2 :y} color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (Imgproc/line src (Point. x1 y1) (Point. x2 y2) (java-scalar color) thickness
                line-type)
  src)

(defmethod-py line :ndarray
  [src {x1 :x y1 :y} {x2 :x y2 :y} color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (cv2/line src [x1 y1] [x2 y2] (python-scalar color) thickness line-type))


(defmulti log
  "Based on Core/log
   -----------------------------------------------------------------------
   Computes the natural log for each item in the src matrix. Input is
   [src {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod log :java-mat
  [^Mat src & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/log src dst)
    dst))

(defmethod-py log :ndarray
  [src & {:keys [dst]}]
  (cv2/log src dst))

(defmethod-cuda log :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/log src dst))

(defn log!
  "Variant of log in which src is also the destination matrix dst."
  [src & rest]
  (apply log src (concat rest [:dst src])))


(defmulti magnitude
  "Based on Core/magnitude
   -----------------------------------------------------------------------
   Computes the magnitude (square root of the squares of the values) for each
   pair of values in src1 and src2. The source matrices must be floats. Input is
   [src1 src2 {:dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod magnitude :java-mat
  [^Mat src1 ^Mat src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/magnitude src1 src2 dst)
    dst))

(defmethod-py magnitude :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/magnitude src1 src2 dst))

(defmethod-cuda magnitude :cuda-gpu-mat
  [src1 src2 & {:keys [dst]}]
  (cuda/magnitude src1 src2 dst))

(defn magnitude!
  "Variant of magnitude in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply magnitude src1 (concat rest [:dst src1])))


(defmulti max
  "Based on Core/max
   -----------------------------------------------------------------------
   Calculates the per-element maximum of two arrays, or one array and a constant.
   Input is
   [src1 src2 {:dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod max :java-mat
  [^Mat src1 src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/max src1 (java-scalar src2) dst)
    dst))

(defmethod-py max :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/max src1 (python-scalar src2) dst))

(defmethod-cuda max :cuda-gpu-mat
  [src1 src2 & {:keys [dst]}]
  (cuda/max src1 (python-scalar src2) dst))

(defn max!
  "Variant of max in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply max src1 (concat rest [:dst src1])))


(defmulti max-value
  "Based on Core/minMaxLoc
   -----------------------------------------------------------------------
   Returns the max value for a single-channel matrix. Input is
   [src {:mask}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod max-value :java-mat
  [^Mat src & {:keys [mask]}]
  (let [mask (or mask (Mat.))
        minMaxLoc (if mask
                    (Core/minMaxLoc src mask)
                    (Core/minMaxLoc src))]
    (-> src ^Core$MinMaxLocResult (Core/minMaxLoc mask) (.maxVal))))

(defmethod-py max-value :ndarray
  [src & {:keys [mask]}]
  (let [[_ maxVal] (vec (cv2/minMaxLoc src mask))]
    maxVal))


(defn mean
  "Based on Core/add (see mean-value for an implementation of Core/mean)
   -----------------------------------------------------------------------
   Calculates the per-element mean of two arrays, or one array and a constant.
   Input is
   [src1 src2 {:dst}]"
  [src1 src2 & {:keys [dst]}]
  (-> (add src1 src2 :dst dst) (divide! 2.0)))

(defn mean!
  "Variant of mean in which src is also the destination matrix dst."
  [src & rest]
  (apply mean src (concat rest [:dst src])))


(defmulti mean-value
  "Based on Core/mean
   -----------------------------------------------------------------------
   Returns the mean value for a matrix. Returns a single number for a single-channel
   matrix, or a vector of numbers for a multi-channel matrix.
   Input is
   [src {:mask}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod mean-value :java-mat
  [^Mat src & {:keys [mask]}]
  (java-value (Core/mean src (or mask (Mat.))) src))

(defmethod-py mean-value :ndarray
  [src & {:keys [mask]}]
  (python-value (cv2/mean src mask) src))


(defmulti merge
  "Based on Core/merge
   -----------------------------------------------------------------------
   Takes one or more matrices and returns a new, composite matrix in which
   each of those original matrices is one channel. Input is
   [src-1 src-2 src-3...]"
  (fn [& sources] (mat-type (first sources))))

(defmethod merge :java-mat
  [& sources]
  (let [channels (java.util.ArrayList.)
        dst (Mat.)]
    (doseq [^Mat src sources]
      (.add channels src))
    (Core/merge channels dst)
    dst))

(defmethod-py merge :ndarray
  [& sources]
  (cv2/merge sources))

(defmethod-cuda merge :cuda-gpu-mat
  [& sources]
  (let [src (first sources)
        dst (cv2/cuda_GpuMat
             (gpu-size src)
             (depth+channels->type (py/$a src depth) (py/$a src channels)))]
    (cuda/merge sources dst)))


(defmulti min
  "Based on Core/min
   -----------------------------------------------------------------------
   Calculates the per-element minimum of two arrays, or one array and a constant.
   Input is
   [src1 src2 {:dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod min :java-mat
  [^Mat src1 src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/min src1 (java-scalar src2) dst)
    dst))

(defmethod-py min :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/min src1 (python-scalar src2) dst))

(defmethod-cuda min :cuda-gpu-mat
  [src1 src2 & {:keys [dst]}]
  (cuda/min src1 (python-scalar src2) dst))

(defn min!
  "Variant of min in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply min src1 (concat rest [:dst src1])))


(defmulti min-area-rect
  "Based on Imgproc/minAreaRect
   -----------------------------------------------------------------------
   Takes a matrix of points (for example, a contour) and returns the minimum rotated
   rectangle that can enclose them. The rotated rectangle is in the form
   {:angle angle :center {:x x :y y} :width width :height height}. Input is
   [src]

   NOTE: Not defined for GPU matrices."
  (fn [src & rest] (general-mat-type src)))

(defmethod min-area-rect :java-mat
  [src]
  (let [rect (-> src
                 (cond-> (not= (.depth src) CV_32F) (convert-to CV_32F))
                 Imgproc/minAreaRect)
        size (.size rect)
        center (.center rect)]
    {:angle (.angle rect) :center {:x (.x center) :y (.y center)}
     :width (.width size) :height (.height size)}))

(defmethod-py min-area-rect :ndarray
  [src]
  (let [[center size angle] (vec (cv2/minAreaRect src))
        [x y] (vec center)
        [width height] (vec size)]
    {:angle angle :center {:x x :y y} :width width :height height}))


(defmulti min-max-loc
  "Based on Core/minMaxLoc
   -----------------------------------------------------------------------
   Finds the locations and values for the minimum and maximum points in a
   single-channel array. Input is
   [src {:mask mask}]
   Returns
   {:min-loc :min-val :max-loc :max-val}

   NOTE: Not currently implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod min-max-loc :java-mat
  [^Mat src & {:keys [mask]}]
  (let [^Core$MinMaxLocResult
        minMaxLoc (if mask
                    (Core/minMaxLoc src mask)
                    (Core/minMaxLoc src))
        ^Point minLoc (.minLoc minMaxLoc)
        ^Point maxLoc (.maxLoc minMaxLoc)]

    {:min-loc {:x (.x minLoc) :y (.y minLoc)} :min-val (.minVal minMaxLoc)
     :max-loc {:x (.x maxLoc) :y (.y maxLoc)} :max-val (.maxVal minMaxLoc)}))

(defmethod-py min-max-loc :ndarray
  [src & {:keys [mask]}]
  (let [[minVal maxVal minLoc maxLoc] (vec (cv2/minMaxLoc src mask))
        [minX minY] (vec minLoc)
        [maxX maxY] (vec maxLoc)]
    {:min-loc {:x minX :y minY} :min-val minVal
     :max-loc {:x maxX :y maxY} :max-val maxVal}))


(defmulti min-value
  "Based on Core/minMaxLoc
   -----------------------------------------------------------------------
   Returns the min value for a single-channel matrix. Input is
   [src {:mask}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod min-value :java-mat
  [^Mat src & {:keys [mask]}]
  (let [mask (or mask (Mat.))
        minMaxLoc (if mask
                    (Core/minMaxLoc src mask)
                    (Core/minMaxLoc src))]
    (-> src ^Core$MinMaxLocResult (Core/minMaxLoc mask) (.minVal))))

(defmethod-py min-value :ndarray
  [src & {:keys [mask]}]
  (let [[minVal] (vec (cv2/minMaxLoc src mask))]
    minVal))


(defmulti mix-channels
  "Based on Core/mixChannels
   -----------------------------------------------------------------------
   Takes a sequence of one or more src matrices and a sequence of one or more dst
   matrices (which should already be allocated). Writes channels from the src
   matrices to the dst matrices as specified by from-to. For example, if from-to
   is [0 0 2 1 1 2], this would indicate the following mappings from channels in
   the src matrices to channels in the dst matrices: 0 => 0, 2 => 1, 1 => 2. Returns
   the first item in dsts.
   Input is
   [srcs dsts from-to]

   NOTE: Not implemented for GPU matrices."
  (fn [[src1] & rest] (mat-type src1)))

(defmethod mix-channels :java-mat
  [srcs dsts from-to]
  (Core/mixChannels (java.util.ArrayList. srcs) (java.util.ArrayList. dsts)
                    (MatOfInt. (into-array Integer/TYPE from-to)))
  (first dsts))

(defmethod-py mix-channels :ndarray
  [srcs dsts from-to]
  (cv2/mixChannels srcs dsts from-to)
  (first dsts))


(defmulti non-zero-bounds
  "Based on Core/findNonZero
   -----------------------------------------------------------------------
   Returns a {:x x :y y :width w :height h} region describing the bounds of all
   non-zero elements in a binary matrix of type CV_8UC1 (typically a mask created
   by in-range, etc). Input is
   [src]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod non-zero-bounds :java-mat
  [^Mat src]
  (let [pts (MatOfPoint.)]
    (Core/findNonZero src pts)
    (let [rect ^Rect (Imgproc/boundingRect pts)]
      {:x (.x rect) :y (.y rect) :width (.width rect) :height (.height rect)})))

(defmethod-py non-zero-bounds :ndarray ;;<---Test
  [src]
  (let [[x y w h] (-> (cv2/findNonZero src) cv2/boundingRect vec)]
    {:x x :y y :width w :height h}))


(defmulti normalize
  "Based on Core/normalize
   -----------------------------------------------------------------------
   Performs a normalizing operation on src. If the norm type is NORM_MINMAX, then
   alpha is the min and beta is the max. If the norm type is NORM_L1 or NORM_L2,
   then alpha is the value we're normalizing to.
   If dst isn't provided, then dst-type can be provided to specify the desired
   depth for the output. Input is
   [src alpha beta norm-type {:dst :dst-type :mask}]"
  (fn [src & rest] (mat-type src)))

(defmethod normalize :java-mat
  [^Mat src alpha beta norm-type & {:keys [dst dst-type mask] :or {dst-type -1}}]
  (let [dst (or dst (Mat.))]
    (if mask
      (Core/normalize src dst alpha beta norm-type dst-type mask)
      (Core/normalize src dst alpha beta norm-type dst-type))
    dst))

(defmethod-py normalize :ndarray
  [src alpha beta norm-type & {:keys [dst dst-type mask] :or {dst-type -1}}]
  (cv2/normalize src dst alpha beta norm-type dst-type mask))

(defmethod-cuda normalize :cuda-gpu-mat
  [src alpha beta norm-type & {:keys [dst dst-type mask] :or {dst-type (py/$a src type)}}]
  (cuda/normalize src alpha beta norm-type dst-type dst mask))

(defn normalize!
  "Variant of normalize in which src is also the destination matrix dst."
  [src & rest]
  (apply normalize src (concat rest [:dst src])))


(defmulti morphology-ex
  "Based on Imgproc/morphologyEx
   -----------------------------------------------------------------------
   Performs the specified morphological transformation on the source matrix,
   (e.g., MORPH_OPEN), using a kernel with the specified [k-width k-height] size.
   Input is
   [src op [k-width k-height] {:dst :iterations :border-type :border-value}]

   NOTE: border-type and border-value are not used for gpu matrices."
  (fn [src & rest] (mat-type src)))

(defmethod morphology-ex :java-mat
  [^Mat src op [k-width k-height] & {:keys [dst iterations border-type border-value]
                                     :or {iterations 1 border-type BORDER_DEFAULT
                                          border-value (Scalar. 0 0 0 0)}}]
  (let [dst (or dst (Mat.))]
    (Imgproc/morphologyEx
     src dst op
     (Imgproc/getStructuringElement Imgproc/MORPH_RECT (Size. k-width k-height))
     (Point. -1 -1) iterations border-type border-value)
    dst))

(defmethod-py morphology-ex :ndarray
  [src op size & {:keys [dst iterations border-type border-value]
                                     :or {iterations 1 border-type BORDER_DEFAULT
                                          border-value (Scalar. 0 0 0 0)}}]
  (cv2/morphologyEx src op (np/ones size np/uint8) dst -1 iterations border-type
                    border-value))

(defmethod-cuda morphology-ex :cuda-gpu-mat
  [src op size & {:keys [dst iterations border-type border-value]
                                     :or {iterations 1}}]
  (let [f (cuda/createMorphologyFilter
           op (py/$a src type) (np/ones size np/uint8) nil iterations)
        dst (or dst (new-mat src))]
    (py/$a f apply src dst)
    dst))

(defn morphology-ex!
  "Variant of morphology-ex in which src is also the destination matrix dst."
  [src & rest]
  (apply morphology-ex src (concat rest [:dst src])))


(defmulti multiply
  "Based on Core/multiply
   -----------------------------------------------------------------------
   Multiply two matrices togther, or multiply one matrix by a constant (src2), which
   can be either a number or a vector of numbers, one for each channel. Input is
   [src1 src2 {:dst}"
  (fn [src1 & rest] (mat-type src1)))

(defmethod multiply :java-mat
  [^Mat src1 src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/multiply src1 (java-scalar src2) dst)
    dst))

(defmethod-py multiply :ndarray
  [src1 src2 & {:keys [dst]}]
  (cv2/multiply src1 (python-scalar src2) dst))

;;Multiplying a cuda-gpu-mat by a scalar is currently unsupported,
;;so we have to make a new cuda-gpu-mat containing the scalar value.
(defmethod-cuda multiply :cuda-gpu-mat
  [src1 src2 & {:keys [dst]}]
   (if (or (number? src2) (vector? src2))
     (cuda/multiply
      src1
      (cv2/cuda_GpuMat (gpu-size src1) (py/$a src1 type) (python-scalar src2))
      dst)
     (cuda/multiply src1 src2 dst)))

(defn multiply!
  "Variant of multiply in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply multiply src1 (concat rest [:dst src1])))


(defmulti optical-flow-DIS
  "Based on DISOpticalFlow/create and .calc
   -----------------------------------------------------------------------
   Uses the DIS algorithm to compute optical flow between two images. Output
   is a two-channel image, where the first channel is x-flow and the second channel
   is y-flow.
   Input is
   [src1 src2 {:preset :dst}]
   where :preset is DIS_FAST, DIS_MEDIUM (default), or DIS_ULTRAFAST"
  (fn [src1 & rest] (mat-type src1)))

(defmethod optical-flow-DIS :java-mat
  [src1 src2 &
   {:keys [preset dst] :or {preset DIS_MEDIUM}}]
  (let [dst (or dst (Mat.))
        flow-obj (DISOpticalFlow/create preset)]
    (.calc flow-obj src1 src2 dst)
    dst))


(defmulti optical-flow-farneback
  "Based on Video/calcOpticalFlowFarneback
   -----------------------------------------------------------------------
   Uses the Farneback algorithm to compute optical flow between two images. Output
   is a two-channel image, where the first channel is x-flow and the second channel
   is y-flow.
   Input is
   [src1 src2 {:pyr_scale :levels :winsize :iterations :oly_n :poly_sigma :flags
    :dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod optical-flow-farneback :java-mat
  [src1 src2 &
   {:keys [pyr_scale levels winsize iterations poly_n poly_sigma flags dst]
    :or {pyr_scale 0.5 levels 3 winsize 15 iterations 3 poly_n 3
         poly_sigma 1.2 flags 0}}]
  (let [dst (or dst (Mat.))]
    (Video/calcOpticalFlowFarneback
     src1 src2 dst pyr_scale levels winsize iterations poly_n poly_sigma flags)
    dst))


(defmethod-py optical-flow-farneback :ndarray
  [src1 src2 &
   {:keys [pyr_scale levels winsize iterations poly_n poly_sigma flags dst]
    :or {pyr_scale 0.5 levels 3 winsize 15 iterations 3 poly_n 3
         poly_sigma 1.2 flags 0}}]
  (cv2/calcOpticalFlowFarneback
   src1 src2 dst pyr_scale levels winsize iterations poly_n poly_sigma flags))

(defmethod-cuda optical-flow-farneback :cuda-gpu-mat
  [src1 src2 &
   {:keys [pyr_scale levels winsize iterations poly_n poly_sigma flags dst]
    :or {pyr_scale 0.75 levels 12 winsize 15 iterations 4 poly_n 7
         poly_sigma 1.75 flags 0}}]
  (let [of (cuda-farneback/create
            :numLevels levels :pyrScale pyr_scale :fastPyramids false
            :winSize winsize :numIters iterations :polyN poly_n
            :polySigma poly_sigma :flags flags)]
    (cuda-optflow/calc of src1 src2 dst)))


(defmulti polar-to-cart
  "Based on Core/polarToCart
   -----------------------------------------------------------------------
   Takes two matrices, one describing an angle and one desribing a magnitude.
   Converts these from polar coordinates to cartesian coordinates. Angle is in
   degrees if :degrees? is true (it's true by default). Input is
   [angle magnitude {:dst-x :dst-y :degrees?}]
   Output is [x-comp y-comp]."
  (fn [angle & rest] (mat-type angle)))

(defmethod polar-to-cart :java-mat
  [^Mat angle ^Mat magnitude & {:keys [dst-x dst-y degrees?] :or {degrees? true}}]
   (let [dst-x (or dst-x (Mat.))
         dst-y (or dst-y (Mat.))]
     (Core/polarToCart magnitude angle dst-x dst-y degrees?)
     [dst-x dst-y]))

(defmethod-py polar-to-cart :ndarray
  [angle magnitude & {:keys [dst-x dst-y degrees?] :or {degrees? true}}]
  (let [[dst-x dst-y] (cv2/polarToCart magnitude angle dst-x dst-y degrees?)]
    [dst-x dst-y]))

(defmethod-cuda polar-to-cart :cuda-gpu-mat
  [angle magnitude & {:keys [dst-x dst-y degrees?] :or {degrees? true}}]
  (let [[dst-x dst-y] (cuda/polarToCart magnitude angle dst-x dst-y degrees?)]
    [dst-x dst-y]))


(defmulti pow
  "Based on Core/pow
   -----------------------------------------------------------------------
   Raises every element in the src matrix to the specified power. Input is
   [src power {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod pow :java-mat
  [^Mat src power & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/pow src power dst)
    dst))

(defmethod-py pow :ndarray
  [src power & {:keys [dst]}]
  (cv2/pow src power dst))

(defmethod-cuda pow :cuda-gpu-mat
  [src power & {:keys [dst]}]
  (cuda/pow src power dst))

(defn pow!
  "Variant of pow in which src is also the destination matrix dst."
  [src & rest]
  (apply pow src (concat rest [:dst src])))


(defmulti put-text
  "Based on Imgproc/putText
   -----------------------------------------------------------------------
   Adds text to a matrix and returns the matrix.
   point: Bottom-left corner of the text in the image
   color: A single number or a vector of numbers, one for each channel
   font: Font type, see HersheyFonts
   font-scale: Scale of the font, relative to its default size
   thickness: Line thickness, an integer, default is 1
   line-type: Default is LINE_8, use LINE_AA for anti-aliased
   Input is
   [src text point color {:font :font-scale :thickness :line-type}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod put-text :java-mat
  [^Mat src text {x :x y :y} color &
   {:keys [font font-scale thickness line-type]
    :or {font FONT_HERSHEY_SIMPLEX font-scale 1 thickness 1 line-type LINE_8}}]
  (Imgproc/putText src text (Point. x y) font font-scale (java-scalar color) thickness
                   line-type)
  src)

(defmethod-py put-text :ndarray
  [src text {x :x y :y} color &
   {:keys [font font-scale thickness line-type]
    :or {font FONT_HERSHEY_SIMPLEX font-scale 1 thickness 1 line-type LINE_8}}]
  (cv2/putText src text [x y] font font-scale (python-scalar color) thickness
               line-type))


(defmulti pyr-down
  "Based on Imgproc/pyrDown
   -----------------------------------------------------------------------
   Performs the downsampling step of Gaussian pyramid construction. Blurs the matrix
   and then downsamples it, by default producing a new matrix that is half the
   width and height of the original (rounded up). Input is
   [src {:dst :dst-size :border-type}]

   NOTE: dst-dize and border-type cannot be specified for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod pyr-down :java-mat
  [^Mat src & {:keys [dst dst-size border-type]}]
  (let [dst (or dst (Mat.))
        dst-size (if-let [[w h] dst-size]
                   (Size. w h) (Size.))]
    (if border-type
      (Imgproc/pyrDown src dst dst-size border-type)
      (Imgproc/pyrDown src dst dst-size))
    dst))

(defmethod-py pyr-down :ndarray ;;<--test, and also cuda version
  [src & {:keys [dst dst-size border-type]}]
  (cv2/pyrDown src dst dst-size border-type))

(defmethod-cuda pyr-down :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/pyrDown src dst))


(defmulti pyr-up
  "Based on Imgproc/pyrUp
   -----------------------------------------------------------------------
   Performs the upsampling step of Gaussian pyramid construction. Upsamples the
   matrix and then blurs it, by default producing a new matrix that is twice the
   width and height of the original. Input is
   [src {:dst :dst-size :border-type}]

   NOTE: dst-dize and border-type cannot be specified for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod pyr-up :java-mat
  [^Mat src & {:keys [dst dst-size border-type]}]
  (let [dst (or dst (Mat.))
        dst-size (if-let [[w h] dst-size]
                   (Size. w h) (Size.))]
    (if border-type
      (Imgproc/pyrUp src dst dst-size border-type)
      (Imgproc/pyrUp src dst dst-size))
    dst))

(defmethod-py pyr-up :ndarray ;;<--test, and also cuda version
  [src & {:keys [dst dst-size border-type]}]
  (cv2/pyrUp src dst dst-size border-type))

(defmethod-cuda pyr-up :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/pyrUp src dst))


(defmulti rectangle
  "Based on Imgproc/rectangle
   -----------------------------------------------------------------------
   Draws a rectangle onto a matrix and returns the matrix. region is in the form
   {:x x :y y :width width :height height}. thickness is an integer and
   defaults to 1. It can also be the constant FILLED for a filled shape.
   line-type defaults to LINE_8 (use LINE_AA for antialiased).
   Color can be a single number or a vector of numbers, one for each channel. Input is
   [src region color {:thickness :line-type}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod rectangle :java-mat
  [^Mat src {x :x y :y :as region} color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (Imgproc/rectangle src (Rect. x y (reg/width region) (reg/height region))
                     (java-scalar color) thickness line-type)
  src)

(defmethod-py rectangle :ndarray
  [src {x :x y :y :as region} color &
   {:keys [thickness line-type] :or {thickness 1 line-type LINE_8}}]
  (cv2/rectangle src [x y] [(reg/max-x region) (reg/max-y region)]
                 (python-scalar color) thickness line-type))


(defmulti reduce
  "Based on Core/reduce
   -----------------------------------------------------------------------
   Reduces a 2d matrix to form a 1d matrix. dim determines the dimension that gets
   reduced: 0 for reducing height to 1, or 1 for reducing width to 1. rtype is
   the type of reduction, e.g., REDUCE_SUM. Input is
  [src dim rtype {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod reduce :java-mat
  [^Mat src dim rtype & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/reduce src dst dim rtype)
    dst))

(defmethod-py reduce :ndarray
  [src dim rtype & {:keys [dst]}]
  (cv2/reduce src dim rtype dst))

(defmethod-cuda reduce :cuda-gpu-mat ;;<----Test
  [src dim rtype & {:keys [dst]}]
  (cv2/reduce src dim rtype dst))


(defmulti reshape
  "Based on .reshape
   -----------------------------------------------------------------------
   Reshapes a matrix to a new [with height] size and potentially a new number of
   channels, while keeping the total number of elements in the matrix constant.
   If channels is not specified, it will be the same as in the src matrix.
   Input options are
   [src [width height]]
   [src [width height] channels]"
  (fn [src & rest] (mat-type src)))

(defmethod reshape :java-mat
  ([^Mat src [_ height]]
   (.reshape src (.channels src) height))
  ([^Mat src [_ height] channels]
   (.reshape src channels height)))

(defmethod-py reshape :ndarray
  ([src [width height]]
   (let [channels (channels src)]
     (if (> channels 1)
       (np/reshape src [height width channels])
       (np/reshape src [height width]))))
  ([src [width height] channels]
   (np/reshape src [height width channels])))

(defmethod-cuda reshape :cuda-gpu-mat
  ([src [_ height]]
   (py/$a src reshape (py/$a src channels) height))
  ([src [_ height] channels]
   (py/$a src reshape channels height)))


(defmulti resize
  "Based on Imgproc/resize
   -----------------------------------------------------------------------
   Resizes a matrix. Can resize to a target [width height] size or resize by
   multiplying src's width and height by a scaling-factor (applying the same scaling
   to both dimensions). Input options are
  [src [width height] {:interpolation :dst}]
  [src scaling-factor {:interpolation :dst}]

  NOTE: src matrix should be of depth CV_32F."
  (fn [src & rest] (mat-type src)))

(defmethod resize :java-mat
  [^Mat src size & {:keys [interpolation dst]}]
  (let [dst (or dst (Mat.))
        interpolation (or interpolation (default-interp src))
        [width height] (when (vector? size) size)]
    (if width
      (Imgproc/resize src dst (Size. width height) 0 0 interpolation)
      (Imgproc/resize src dst (Size.) (double size) (double size) interpolation))
    dst))

(defmethod-py resize :ndarray
  [src size & {:keys [interpolation dst]}]
  (let [interpolation (or interpolation (default-interp src))]
    (if (vector? size)
      (cv2/resize src size dst 0 0 interpolation)
      (cv2/resize src [0 0] dst size size interpolation))))

(defmethod-cuda resize :cuda-gpu-mat
  [src size & {:keys [interpolation dst]}]
  (let [interpolation (or interpolation (default-interp src))]
    (if (vector? size)
      (cuda/resize src size dst 0 0 interpolation)
      (cuda/resize src [0 0] dst size size interpolation))))


(defmulti rotate
  "Based on Core/rotate
   -----------------------------------------------------------------------
   Rotates a matrix. Example of a rotation type is ROTATE_90_COUNTERCLOCKWISE.
   Input is
   [src rotation-type {:dst}]

   NOTE: Not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod rotate :java-mat
  [^Mat src rotation-type & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/rotate src dst rotation-type)
    dst))

(defmethod-py rotate :ndarray
  [src rotation-type & {:keys [dst]}]
  (cv2/rotate src rotation-type))

(defn rotate!
  "Variant of rotate in which src is also the destination matrix dst. The matrix's
   width and height should be equal."
  [src & rest]
  (apply rotate src (concat rest [:dst src])))


(defmulti scale-add
  "Based on Core/scaleAdd
   ---------------------------------------------------------------------------
   Multiplies one matrix by a number and then adds it to a second matrix. Input is
   [src1 alpha src2 {:dst}]

   NOTE: Not implemented for GPU matrices."
  (fn [src1 & rest] (mat-type src1)))

(defmethod scale-add :java-mat
  [^Mat src1 alpha ^Mat src2 & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/scaleAdd src1 alpha src2 dst)
    dst))

(defmethod-py scale-add :ndarray
  [src1 alpha src2 & {:keys [dst]}]
  (cv2/scaleAdd src1 alpha src2 dst))

(defn scale-add!
  "Variant of add in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply scale-add src1 (concat rest [:dst src1])))


(defmulti sep-filter-2D
  "Based on Imgproc/sepFilter2D
   -----------------------------------------------------------------------
   Applies a separable linear filter to a matrix, convolving it with two kernels
   that represent the x and y dimensions of the filter. If dst is not provided
   dst-type can indicate the desired depth of the destination matrix dst (default
   is to match the src). delta is a number that will be added to all filtered
   values in dst. border-type indicates how to extrapolate information at the
   edge of the image. Input is
   [src kernel-x kernel-y {:dst :dst-type :delta :border-type}]"
  (fn [src & rest] (mat-type src)))

(defmethod sep-filter-2D :java-mat
  [^Mat src ^Mat kernel-x ^Mat kernel-y & {:keys [dst dst-type delta border-type]
                           :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (let [dst (or dst (new-mat src :depth (when (>= dst-type 0) dst-type)))]
    (Imgproc/sepFilter2D src dst dst-type kernel-x kernel-y (Point. -1 -1) delta
                         border-type)
    dst))

(defmethod-py sep-filter-2D :ndarray
  [src kernel-x kernel-y & {:keys [dst dst-type delta border-type]
                            :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (cv2/sepFilter2D src dst-type kernel-x kernel-y dst nil delta border-type))

(defmethod-cuda sep-filter-2D :cuda-gpu-mat
  [src kernel-x kernel-y & {:keys [dst dst-type delta border-type]
                           :or {dst-type -1 delta 0 border-type BORDER_DEFAULT}}]
  (let [kernel-x (->numpy kernel-x)
        kernel-y (->numpy kernel-y)
        src-type (py/$a src type)
        dst-type (if (>= dst-type 0)
                    dst-type
                    src-type)
        f (cuda/createSeparableLinearFilter src-type dst-type kernel-y kernel-x
                                   nil border-type border-type)
        dst (or dst (new-mat src :depth (when (>= dst-type 0) dst-type)))]
    (py/$a f apply src dst)
    dst))

(defn sep-filter-2D!
  "Variant of sep-filter-2D in which src is also the destination matrix dst."
  [src & rest]
  (apply sep-filter-2D src (concat rest [:dst src])))


(defmulti set-to
  "Based on .setTo
   -----------------------------------------------------------------------
   Either sets all values in src to some value (either a number
   that will be used for all channels or a vector of numbers, one for each channel)
   or sets all values in src to the corresponding values in another matrix. An
   optional mask can be specified. Returns the src matrix. Input is
   [src value {:mask}]"
  (fn [src & rest] (mat-type src)))

;;If value is a matrix, we need to use copy instead.
(defmethod set-to :java-mat
  [^Mat src value & {:keys [mask]}]
  (cond
    (not (or (number? value) (coll? value)))
    (copy value :dst src :mask mask)
    mask
    (.setTo src (java-scalar value) mask)
    :else
    (.setTo src (java-scalar value)))
  src)

(defmethod-py set-to :ndarray
  [src value & {:keys [mask]}]
  (cond
    (not (or (number? value) (coll? value)))
    (cv2/copyTo value src mask)

    (and (nil? mask) (number? value))
    (py/set-item! src [] value)

    (nil? mask)
    (py/set-item! src [] (python-scalar2 value (channels src)))

    :else
    (cv2/copyTo (new-mat src :value value) src mask))
  src)

(defmethod-cuda set-to :cuda-gpu-mat
  [src value & {:keys [mask]}]
  (if (not (or (number? value) (coll? value)))
    (py/$a value copyTo src mask)
    (py/$a src setTo value mask))
  src)


(declare submat)
(defmulti set-value
  "Based on .put
   -----------------------------------------------------------------------
   Changes the value of a single element in a matrix. Value can be either a single
   number that will be used for all channels or a vector of numbers, one for
   each channel. Returns the src matrix. Input options are:
   [src x y value]
   [src {:x x :y y} value]"
  (fn [src & rest] (mat-type src)))

(defmethod set-value :java-mat
  ([^Mat src x y value]
   (->> (cond->> value (number? value) (repeat (.channels src)))
        ((type->arrayfn (.type src)))
        (.put src y x))
   src)
  ([src {x :x y :y} value]
   (set-value src x y value)))

(defmethod-py set-value :ndarray
  ([src x y value]
   (if (number? value)
     (py/set-item! src [y x] value)
     (py/set-item! src [y x] (python-scalar2 value (channels src))))
   src)
  ([src {x :x y :y} value]
   (set-value src x y value)))

(defmethod-cuda set-value :cuda-gpu-mat
  ([src x y value]
   (set-to (submat src {:x x :y y}) value)
   src)
  ([src {x :x y :y} value]
   (set-to (submat src {:x x :y y}) value)
   src))


(defmulti set-values
  "Based on .put
   -----------------------------------------------------------------------
   Changes the values of multiple elements in a matrix. Values should be an
   appropriately typed java array of numbers. This operation will begin at the
   location specified by {:x x :y y} and continue changing values in the matrix,
   in the order of channels, then rows, then columns, until it reaches the end
   of the values array. Returns the src matrix. Input options are:
   [src x y values]
   [src {:x x :y y} values]

   NOTE: This method is not implemented for numpy arrays or GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod set-values :java-mat
  ([^Mat src x y values]
   (.put src y x values)
   src)
  ([src {x :x y :y} values]
   (.put src y x values)
   src))


(defmulti sobel
  "Based on Imgproc/Sobel
   -----------------------------------------------------------------------
   Computes the first, second, or third derivative for x, y, or both dimensions
   using a Sobel operator. dx and dy should each be 0 (meaning don't compute
   for this dimension), 1, 2, or 3 (for first, second, or third derivative).
   ksize is the size (the width and also the height) of the Sobel kernel. scale
   is an optional scale factor for computed derivative values. If dst is not provided,
   dst-depth can be used to specify the depth of the destination matrix. Input is
   [src dx dy ksize {:dst :dst-depth :scale :border-type}]"
  (fn [src & rest] (mat-type src)))

(defmethod sobel :java-mat
  [^Mat src dx dy ksize & {:keys [dst dst-depth scale border-type]
                           :or {dst-depth (or (some-> dst (.depth))
                                             (.depth src))
                                scale 1
                                border-type BORDER_DEFAULT}}]
   (let [dst (or dst (Mat.))]
     (Imgproc/Sobel src dst dst-depth dx dy ksize scale 0 border-type)
     dst))

(defmethod-py sobel :ndarray
  [src dx dy ksize & {:keys [dst dst-depth scale border-type]
                      :or {dst-depth
                           (or (some-> dst (py/get-attr :dtype) str dtype->depth)
                               (-> src (py/get-attr :dtype) str dtype->depth))
                           scale 1
                           border-type BORDER_DEFAULT}}]
  (cv2/Sobel src dst-depth dx dy dst ksize scale 0 border-type))

(defmethod-cuda sobel :cuda-gpu-mat
  [src dx dy ksize & {:keys [dst dst-depth scale border-type]
                      :or {dst-depth
                           (or (some-> dst (py/$a depth))
                               (py/$a src depth))
                           scale 1
                           border-type BORDER_DEFAULT}}]
  (let [dst (or dst (new-mat src :depth dst-depth))
        sfilter
        (cuda/createSobelFilter (py/$a src depth) dst-depth dx dy ksize scale
                                border-type border-type)]
    (py/$a sfilter apply src dst)
    dst))

(defn sobel!
  "Variant of sobel in which src is also the destination matrix dst."
  [src & rest]
  (apply sobel src (concat rest [:dst src])))


(defmulti split
  "Based on Core/split
   -----------------------------------------------------------------------
   Takes a multi-channel matrix and returns a vector of matrices, one for each
   channel. Inputs are
  [src]"
  (fn [src] (mat-type src)))

(defmethod split :java-mat [src]
  (let [parts (java.util.ArrayList.)]
    (Core/split src parts)
    (vec parts)))

(defmethod-py split :ndarray [src]
  (vec (cv2/split src)))

(defmethod-cuda split :cuda-gpu-mat [src]
  (vec (cuda/split src)))


(defmulti sqrt
  "Based on Core/sqrt
   -----------------------------------------------------------------------
   Computes the square root of each item in the src matrix. Input is
   [src {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod sqrt :java-mat
  [^Mat src & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/sqrt src dst)
    dst))

(defmethod-py sqrt :ndarray
  [src & {:keys [dst]}]
  (cv2/sqrt src dst))

(defmethod-cuda sqrt :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/sqrt src dst))

(defn sqrt!
  "Variant of sqrt in which src is also the destination matrix dst."
  [src & rest]
  (apply sqrt src (concat rest [:dst src])))


(defmulti submat
  "Based on .submat
   -----------------------------------------------------------------------
   Returns a submatrix of a larger matrix. If width is undefined but x is defined,
   assumes width is 1. If both are undefined, assumes the submatrix ranges from
   0 to (width src). Does the same for y/height.
   Input options are:
   [src x y width height]
   [src {:x x :y y :width width :height height}]"
  (fn [src & rest] (mat-type src)))

(defmethod submat :java-mat
  ([src x y width height]
   (let [[sx sy swidth sheight] (x-y-w-h x y width height src)]
     (.submat src sy (+ sy sheight) sx (+ sx swidth))))
  ([src bounds]
   (let [[sx sy swidth sheight] (x-y-w-h bounds src)]
     (.submat src sy (+ sy sheight) sx (+ sx swidth)))))

(defmethod-py submat :ndarray
  ([src x y width height]
   (let [[sx sy swidth sheight] (x-y-w-h x y width height src)]
     (py/get-item src [(builtins/slice (int sy) (int (+ sy sheight)))
                       (builtins/slice (int sx) (int (+ sx swidth)))])))
  ([src bounds]
   (let [[sx sy swidth sheight] (x-y-w-h bounds src)]
     (py/get-item src [(builtins/slice (int sy) (int (+ sy sheight)))
                       (builtins/slice (int sx) (int (+ sx swidth)))]))))

(defmethod-cuda submat :cuda-gpu-mat
  ([src x y width height]
   (let [[sx sy swidth sheight] (x-y-w-h x y width height src)]
     (cv2/cuda_GpuMat src (mapv int [sx sy swidth sheight]))))
  ([src bounds]
   (let [[sx sy swidth sheight] (x-y-w-h bounds src)]
     (cv2/cuda_GpuMat src (mapv int [sx sy swidth sheight])))))


(defmulti subtract
  "Based on Core/subtract
   -----------------------------------------------------------------------
   Subtract a matrix from another, or subtract a constant (src2) from one matrix,
   where the constant can be either a number or a vector of numbers, one for each
   channel. Input is
   [src1 src2 {:mask :dst}]"
  (fn [src1 & rest] (mat-type src1)))

(defmethod subtract :java-mat
  [^Mat src1 src2 & {:keys [mask dst]}]
   (let [dst (or dst (Mat.))]
     (Core/subtract src1 (java-scalar src2) dst (or mask (Mat.)))
     dst))

(defmethod-py subtract :ndarray
  [src1 src2 & {:keys [mask dst]}]
  (cv2/subtract src1 (python-scalar src2) dst mask))

;;Subtracting a scalar from a cuda-gpu-mat is currently unsupported,
;;so we have to make a new cuda-gpu-mat containing the scalar value.
(defmethod-cuda subtract :cuda-gpu-mat
  [src1 src2 & {:keys [mask dst]}]
   (if (or (number? src2) (vector? src2))
     (cuda/subtract
      src1
      (cv2/cuda_GpuMat (gpu-size src1) (py/$a src1 type) (python-scalar src2))
      dst mask)
     (cuda/subtract src1 src2 dst mask)))

(defn subtract!
  "Variant of subtract in which src1 is also the destination matrix dst."
  [src1 & rest]
  (apply subtract src1 (concat rest [:dst src1])))


(defmulti sum-elems
  "Based on Core/sumElems
   -----------------------------------------------------------------------
   Returns the sum of values in a matrix. Returns a single number for a single-channel
   matrix, or a vector of numbers for a multi-channel matrix. Input is
   [src]

   NOTE: This method is not implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod sum-elems :java-mat
  [^Mat src]
  (-> (Core/sumElems src) (java-value src)))

(defmethod-py sum-elems :ndarray
  [src]
  (python-value (cv2/sumElems src) src))


(defmulti threshold
  "Based on Imgproc/threshold
   -----------------------------------------------------------------------
   Computes a threshold operation on src, using the specified threshold value.
   Some threshold types (THRESH_BINARY, THRESH_BINARY_INV) require a max-value,
   which defaults to 1.0.
   Search for \"opencv threshold types\" to see the range of types available.
   Input is
   [src threshold-value threshold-type {:dst :max-value}]"
  (fn [src & rest] (mat-type src)))

(defmethod threshold :java-mat
  [^Mat src thresh type & {:keys [dst max-value] :or {max-value 1.0}}]
  (let [dst (or dst (Mat.))]
    (Imgproc/threshold src dst thresh max-value type)
    dst))

(defmethod-py threshold :ndarray
  [src thresh type & {:keys [dst max-value] :or {max-value 1.0}}]
  (-> (cv2/threshold src thresh max-value type dst) vec second))

(defmethod-cuda threshold :cuda-gpu-mat
  [src thresh type & {:keys [dst max-value] :or {max-value 1.0}}]
  (-> (cuda/threshold src thresh max-value type dst) vec second))

(defn threshold!
  "Variant of threshold in which src is also the destination matrix dst."
  [src & rest]
  (apply threshold src (concat rest [:dst src])))


(defmulti transform-bgr
  "Based on a portion of the functionality of Core/transform
   -----------------------------------------------------------------------
   Takes a three-channel matrix and a [b g r] weight vector and returns a single-channel
   matrix computed by applying the weights to the source. For example, [0 -1 1]
   would produce a matrix by subtracing the green channel from the red channel in
   the original matrix. Input is
   [src weights {:dst}]

   NOTE: Not currently implemented for GPU matrices."
  (fn [src & rest] (mat-type src)))

(defmethod transform-bgr :java-mat
  [^Mat src weights & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/transform src dst (-> weights float-array (MatOfFloat.) (.t)))
    dst))

(defmethod-py transform-bgr :ndarray
  [src weights & {:keys [dst]}]
  (cv2/transform src (-> weights float-array np/array cv2/transpose) dst))


(defmulti transpose
  "Based on Core/transpose
   -----------------------------------------------------------------------
   Transposes a matrix. Input is
   [src {:dst}]"
  (fn [src & rest] (mat-type src)))

(defmethod transpose :java-mat
  [^Mat src & {:keys [dst]}]
  (let [dst (or dst (Mat.))]
    (Core/transpose src dst)
    dst))

(defmethod-py transpose :ndarray
  [src & {:keys [dst]}]
  (cv2/transpose src dst))

(defmethod-cuda transpose :cuda-gpu-mat
  [src & {:keys [dst]}]
  (cuda/transpose src dst))

(defn transpose!
  "Variant of transpose in which src is also the destination matrix dst. The matrix's
   width and height should be equal."
  [src & rest]
  (apply transpose src (concat rest [:dst src])))


(defmulti type
  "Based on .type
   -----------------------------------------------------------------------
   Returns a matrix's type."
  (fn [src] (general-mat-type src)))

(defmethod type :java-mat
  [src]
  (.type src))

(defmethod-py type :ndarray
  [src]
  (depth+channels->type (depth src) (channels src)))

(defmethod-cuda type :cuda-gpu-mat
  [src]
  (py/$a src type))


(defmulti vconcat
  "Based on Core/vconcat
   -----------------------------------------------------------------------
   Takes one or more matrices, each of which should have the same number of
   rows, and concatenates them horizontally.  Input is
   [src-1 src-2 ...]

   NOTE: Not currently implemented for gpu mats."
  (fn [& sources] (mat-type (first sources))))

(defmethod vconcat :java-mat
  [& sources]
  (let [dst (Mat.)]
    (Core/vconcat (ArrayList. sources) dst)
    dst))

(defmethod-py vconcat :ndarray
  [& sources]
  (cv2/vconcat sources))

; For testing in the repl...
; (defn rf [] (refresh) (ns-unalias *ns* 'cv) (require '[arcadia.utility.opencv :as cv]))
