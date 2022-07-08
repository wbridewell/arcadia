(ns
  ^{:doc "Functions for displaying an array of images for debugging."}
  arcadia.utility.image-array
  (:import [javax.swing JFrame JPanel JLabel JScrollPane ImageIcon BoxLayout BorderFactory]
           [java.awt GridLayout])
  (:require [clojure.java.io :as io]
            [clojure.math.numeric-tower :as math]
            [arcadia.utility [general :as g] [image :as img] [opencv :as cv]
             [swing :refer [invoke-now]]]))

;;;;;;;;;;;;;;;;;
;; This file contains utility functions to support displaying and saving
;; sequences of images, for debugging purposes. Images can be either opencv
;; matrices or buffered images.


;; UPDATE: You should now wrap the macro do-and-display-images around any
;; code that will use the image-array. When this code concludes, the image-array
;; will be displayed. Note, however, that if you don't want to use image-array's
;; global queue of images and instead want to pass your own list of images
;; directly to display-image-array, that option is still available.


;; Functions:
;;
;; add-image
;;     Adds an image (and, optionally, a label) to the queue of images.
;; reset-images
;;     Resets the queue of images.
;; display-image-array
;;     Displays the queue of images in a 2D grid. The one required argument
;;     specifies whether labels should be displayed also. Try using the
;;     default keyword arguments. Alternatively, you can specify your own list
;;     of images and labels with the :images and :labels keywords.
;; animate-images
;;     Displays an animation that cycles through the queued images. Again,
;;     the one required argument specifies whether labels should be displayed,
;;     and you can use the :images and :labels keywords.
;; save-images
;;     Saves the queued images as jpegs. The :directory keyword can be used to
;;     specify the directory in which the images should be saved. The default
;;     directory is "images" meaning the images will be saved in an
;;     "arcadia/images" directory that will be created if necessary.
;; load-images
;;     Loads a list of images and their corresponding labels from the specified
;;     directory. These lists replace the global queue, so they are then available
;;     for display-image-array, animate-images, or save-images. Alternatively, for
;;     display-image-array and animate-images, you can specify a keyword
;;     :directory to load the images just for a single call of that function.
;;
;; Further note: The :img-w and :img-h keywords can be used to specify the width
;; and height of the images. These keywords have default values for
;; display-image-array and animate-images, but for save-images the default is to
;; use the images' original sizes. Because jpg is a lossy format, if you plan to
;; save the images to files and reload them later, it is better to rescale them
;; before saving.


(def ^:dynamic ^:private *images* "Queue of images to be displayed" [])

(defn- format-image
  "Convert an image to bufferedimage if it isn't that format already, and resize
  the image if w and h are non-nil. Currently, this code will convert bufferedimages
  to opencv Mats before resizing because the opencv scaling algorithm appears better."
  [img w h]
  (img/mat-to-bufferedimage
   (img/resize-image
    (g/apply-if #(not= (cv/mat-type %) :java-mat) img/bufferedimage-to-mat img)
    w h true)))

(defn- get-file-number
  "Get the prefixed number of the file's filename."
  [file]
  (second (re-matches #"([0-9]+).*" (.getName file))))

(defn- get-file-label
  "Get the label of a file's filename."
  [file]
  (second (re-matches #".*-(.*?).jpg" (.getName file))))

(defn- make-image-panel
  "Constructs a panel containing a single image and label pair."
  [image label]
  (doto (JPanel.)
    (#(.setLayout % (BoxLayout. % BoxLayout/Y_AXIS)))
    (.setBorder (BorderFactory/createEtchedBorder))
    (.add
      (doto (JLabel. label)
        (.setAlignmentX java.awt.Component/CENTER_ALIGNMENT)
        (.setHorizontalAlignment java.awt.Component/CENTER_ALIGNMENT)))
    (.add
      (doto (JPanel.)
        (.add (JLabel. (ImageIcon. image)))))))


(defn- make-main-panel
  "Adds images to the panel that will hold an array of images."
  [imgs lbls rows cols]
  (invoke-now
    (doto (JPanel.)
      (.setLayout (GridLayout. rows cols))
      ((fn [x] (doall (map #(.add x (make-image-panel %1 %2)) imgs lbls))))
      (.revalidate)
      (.repaint))))

(defn- animate-images-display
  "Displays images sequential to create an animation."
  [name img-w img-h interval use-labels?]
  (invoke-now
    (let [f (doto (JFrame. name)
              (.setLocation 0 0)
              (.setVisible true)
              (.setSize (+ img-w 40) (+ img-h 40)))]
      (doseq [image (map #(format-image (:image %) img-w img-h) *images*)
              label (map :label *images*)]
        (doto (.getContentPane f)
           (.removeAll)
           (.add (make-image-panel image (if use-labels? label "")))
           (.revalidate)
           (.repaint))
        (Thread/sleep interval)))))

(defn- make-frame
  "Set up the frame and its contained panel that will hold an array of images."
  [image-panels name rows cols img-w img-h]
  (invoke-now
    (doto (JFrame. name)
          (.setLocation 0 0)
          (.setVisible true)
          (.setSize (* cols (+ img-w 40)) (* rows (+ img-h 40)))
          (.add (JScrollPane. image-panels)))))

(defn load-images
  "Given a string describing a directory in the arcadia directory, resets
  the global image vector to the images in the directory."
  [directory]
  (when (and directory (thread-bound? #'*images*))
    (->> directory
         io/file
         file-seq
         rest
         (remove (comp nil? get-file-number))
         (sort-by get-file-number)
         (mapv #(hash-map :label (get-file-label %)
                          :image (img/load-bufferedimage (.getName %))))
         (set! *images*))))

(defn add-image
  "Add an image to the queue of images to be displayed."
  ([img]
   (add-image img ""))
  ([img label]
   (when (thread-bound? #'*images*)
     (set! *images* (conj *images* {:label label :image img})))))

(defn reset-images
  "Reset the queue of images to be displayed."
  []
  (when (thread-bound? #'*images*)
    (set! *images* [])))


(defn display-image-array
  "Display an array of images."
  [use-labels? & {:keys [rows cols img-w img-h directory fname]
                  :or {fname "Image Array"
                       rows 30
                       cols 6
                       img-w 240
                       img-h 240}}]
  (when directory (load-images directory))
  (when (seq *images*)
    (let [final-images (take (* rows cols) (map #(format-image (:image %) img-w img-h) *images*))
          final-labels (take (count final-images) (map :label *images*))
          display-rows (inc (int (math/floor (/ (dec (count final-images)) cols))))
          display-cols (min (count final-images) cols)]
      (make-frame
        (make-main-panel final-images final-labels display-rows display-cols)
        fname display-rows display-cols img-w img-h))))

(defn save-images
  "Save a sequence of images to a directory (within the top arcadia directory).
  If the directory does not exist, it will be created. Returns true when files
  were saved."
  [& {:keys [directory img-w img-h]
      :or {directory "images" img-w 240 img-h 240}}]
  (when (seq *images*)
    (.mkdir (java.io.File. directory))
    (doall
      (map #(img/write-bufferedimage %1 (str directory "/" %3 "-" %2 ".jpg"))
           (map #(format-image (:image %) img-w img-h) *images*)
           (map :label *images*)
           (range)))
    true))

(defn animate-images
  "Display an sequence of images."
  [use-labels? & {:keys [rows cols img-w img-h directory fname interval]
                  :or {fname "Animation"
                       rows 30
                       cols 6
                       img-w 240
                       img-h 240
                       interval 500}}]
  (when directory (load-images directory))
  (when (seq *images*) (animate-images-display fname img-w img-h interval use-labels?)))

(defn delete-images
  "Deletes all .jpg files in the given (or default) directory."
  ([] (delete-images "images"))
  ([directory]
   (doseq [f (->> directory
                  io/file
                  file-seq
                  (filter #(.isFile %))
                  (filter #(-> % .toString (.endsWith ".jpg"))))]
     (io/delete-file f true))))

(defmacro do-and-display-images
  "Resets the queue of images to display in the image-array, runs the code (which
  presumably will contain calls to add-image), and finally calls the function
  display-image-array on the list of arguments specified by display-arguments.
  Returns whatever was returned by the code."
  [display-arguments & code]
  `(binding [*images* []]
            (let [results# (atom nil)]
              (arcadia.utility.image-array/reset-images)
              (reset! results#
                      (do ~@code))
              (apply arcadia.utility.image-array/display-image-array ~display-arguments)
              @results#)))
