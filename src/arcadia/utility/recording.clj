(ns
  ^{:doc "Support for recording a model run to images or video."}
  arcadia.utility.recording
  (:require [kawa.core :refer [ffmpeg!]]
            [clojure.java.io :as io]
            (arcadia.utility [general :as g]
                             [image :as img]
                             [parameters :as parameters]
                             [swing :refer [invoke-now]]))
  (:import java.awt.image.BufferedImage
           java.util.concurrent.TimeUnit
           [java.awt Color Font RenderingHints]))

(def ^:parameter previous-state
  "If this is non-nil, we want to continue saving output to an existing file, rather
   than restarting from scratch." nil)

(def ^:parameter movie-directory "directory to which movies will be saved" "movies")

(def ^:parameter image-directory "directory to which images will be saved" "images")

(def ^:parameter image-format "format used for saving images" "png")
(def ^:parameter movie-format "format used for saving movies" "mp4")

(def ^:parameter require-force-to-finish? "If this is true, then ignore any calls
  to finish-recording that don't have the optional argument force? set to true."
  false)

(def ^:parameter make-movie? "Should we make a movie? If not, we'll just save
  the frames as individual images." true)
(def ^:parameter make-images? "Should we make a sequence of images? If not,
  we'll delete any images that have been created after they're used to construct
  the movie." false)

(def ^:parameter recording-file "Name of the directory and/or movie file to be
  created (should not include file extension). If this is nil, a name will be
  generated automatically based on the current time." nil)

(def ^:parameter audio-source "Path and name of an file that will provide the
  audio for the recording file being produced. NOTE: You'll likely want to ensure
  that your recording :frame-rate is the same as the audio source's frame rate."
  nil)

(def ^:parameter skip-frames "Skip this many frames at the beginning of the model
  run before beginnning recording." 1)

(def ^:parameter frame-rate "the framerate (fps) for the movie" 40)
(def ^:parameter video-codec "the video codec for the movie" "libx264")
(def ^:parameter pixel-format "the pixel format for the movie" "yuv420p")
(def ^:parameter constant-rate-factor "the constant rate factor for the movie" 15)

(def ^:parameter image-width "The width of the output images. If this is nil,
  the width will be determined automatically." nil)
(def ^:parameter image-height "The height of the output images. If this is nil,
  the width will be determined automatically." nil)

(def ^:parameter background-color "color of the background on which dialog boxes
  will be shown" (Color. 200 200 200))

(def ^:parameter title-color "color of the text naming each dialog box" Color/BLACK)
(def ^:parameter title-font "font of title text" (Font. "Dialog" Font/PLAIN 14))
(def ^:parameter title-height "height of title text above each dialog box" 8)

(defn- force-even
  "Force an integer to be even"
  [num]
  (if (odd? (int num))
    (+ num 1)
    num))

(defn- jframes->bounds
  "Given a list of JFrames, determine a bounding box for them. But if we already
   have :image-bounds stored in params, just use those."
  [jframes params]
  (cond
    (:image-bounds params)
    (:image-bounds params)

    (seq jframes)
    (let [min-x (apply min (map #(.getX %) jframes))
          min-y (apply min (map #(.getY %) jframes))
          max-x+ (apply max (map #(+ (.getX %) (.getWidth %)) jframes))
          max-y+ (apply max (map #(+ (.getY %) (.getHeight %)) jframes))]
      {:x min-x :y min-y
       :width (or (:image-width params) (force-even (- max-x+ min-x)))
       :height (or (:image-height params) (force-even (- max-y+ min-y)))})

    :else
    {:x 0 :y 0 :width 900 :height 900}))

(defn- drawStringCentered
  "Draws a string of text to the graphics object g. The text will be horizontally
   centered within the specified width, and it will be located above the specified
   height."
  [g text width height]
  (when (and text (not (zero? width)))
    (.drawString
     g text
     (int (/ (- width (.stringWidth (.getFontMetrics g) text)) 2))
     (- height title-height))))

(defn- jframes->image+bounds
  "Draw the list of java frames onto an image. Uses (:image-bounds params) if it's
   available, or else computes the bounds from the visual input. Returns [img bounds]."
  [jframes params]
  (let [{x :x y :y width :width height :height :as bounds} (jframes->bounds jframes params)
        img (BufferedImage. width height BufferedImage/TYPE_INT_BGR)
        g (.createGraphics img)]

    (doto g
          (.setPaint (:background-color params))
          (.fillRect 0 0 width height)
          (.setRenderingHint RenderingHints/KEY_TEXT_ANTIALIASING
                             RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
          (.setColor (:title-color params))
          (.setFont (:title-font params)))

    (invoke-now
     (doseq [jframe jframes]
       (.translate g (- (.getX jframe) x) (- (.getY jframe) y))
       (.paint jframe g)
       (drawStringCentered g (.getTitle jframe) (.getWidth jframe)
                           (- (.getHeight jframe)
                              (.getHeight (.getContentPane jframe))))
       (.translate g (- x (.getX jframe)) (- y (.getY jframe)))))
    [img bounds]))

(defn begin-recording
  "Takes a list of java frames for the first image to be recorded, along with
   a hashmap of parameter values. Records the java frames as an image and returns
   a hashmap describing the recording state. This state hashmap should be passed
   to subsequent calls to record-frame or finish-recording."
  [jframes params]
  (let [[image bounds] (jframes->image+bounds jframes params)
        directory (or (:recording-file params) (g/date+time-string))
        file (str (:image-directory params) "/" directory "/000000."
                  (:image-format params))]
    (g/make-directories-for-path file)
    (when (not (pos? (:skip-frames params)))
      (img/write-bufferedimage image file image-format))
    ;;If we're making a movie, ensure that every image has the same dimensions
    ;;as the first image.
    (assoc params
           :image-bounds (if (:make-movie? params)
                           bounds
                           (:image-bounds params))
           :recording-file directory
           :index 1
           :skip-frames (dec (:skip-frames params)))))

(defn began-recording?
  "Returns true if we have recorded anything."
  [{index :index :as recording-state}]
  (boolean index))

(defn record
  "Takes a list of java frames for the next image to be recorded, along with
   a hashmap with the recording state. Records the java frames as an image and
   returns an updated recording state, which should be passed to subsequent
   calls to record-frame or finish-recording."
  [jframes state]
  (if (not (began-recording? state))
    (begin-recording jframes state)
    (do
      (when (not (pos? (:skip-frames state)))
        (img/write-bufferedimage (first (jframes->image+bounds jframes state))
                             (str (:image-directory state) "/" (:recording-file state)
                                  (format "/%06d." (:index state))
                                  (:image-format state))
                             (:image-format state)))
      (-> state
          (update :index inc)
          (update :skip-frames dec)))))

(defn start
  "Set up the representation that will be used to store state while we are recording
   images or a movie."
  [params]
  (or (:previous-state params) (parameters/merge-parameters params)))

(defn finish-recording
  "Takes a list of java frames for the final image to be recorded, along with
   a hashmap with the recording state. Records the java frames as an image and
   takes any final steps specified in the recording state (saving a video if
   :make-video? is true)."
  ([state]
   (finish-recording state false))
  ([state force?]
   (when (and (:make-movie? state)
              (or force? (not (:require-force-to-finish? state))))
     (g/make-directories-for-path (str (:movie-directory state) "/"
                                       (:recording-file state)))
     (let [info
           (apply ffmpeg!
                  (concat
                   (list :y :framerate (:frame-rate state)
                         :i (str (:image-directory state) "/" (:recording-file state)
                                 "/%06d." (:image-format state)))
                   (when (:audio-source state)
                     (list :i (:audio-source state) :map "0:v" :map "1:a"))
                   (list :pix_fmt (:pixel-format state)
                         :vcodec (:video-codec state)
                         :crf (:constant-rate-factor state)
                         :shortest
                         (str (:movie-directory state) "/" (:recording-file state) "."
                              (:movie-format state)))))]
       ;;Wait until the process is done making the movie before we delete the images.
       (when (not (:make-images? state))
         (when (not (.waitFor (-> info :process :process) 20 TimeUnit/SECONDS))
           (println "SAVING TO VIDEO IS TAKING LONGER THAN EXPECTED..."))
         (when (not (.waitFor (-> info :process :process) 45 TimeUnit/MINUTES))
           (throw (Exception. (str "Timed out while saving to video.")))))))

   (when (and (not (:make-images? state))
              (or force? (not (:require-force-to-finish? state))))
     (let [dirname (str (:image-directory state) "/" (:recording-file state))]
       (doseq [file (.listFiles (io/file dirname))]
         (io/delete-file file))
       (io/delete-file dirname)))))
