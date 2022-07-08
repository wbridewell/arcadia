(ns arcadia.camera.theater
  (:import org.opencv.videoio.VideoCapture
           org.opencv.videoio.Videoio
           org.opencv.core.Mat)
  (:require [clojure.math.numeric-tower :as math]))

(def ^:parameter increment "Amount of time that passes between each frame of video"
  0.025)

(def ^:parameter skip-frames "Skip this many frames at the beginning of the video"
  0)

(def ^:parameter viewing-width "The width of the video corresponds to this many
  degrees of visual angle" nil)

;; This camera is a videoplayer that can be polled by a sensor to get the
;; current frame. The camera returns its full frame. The environment is similar
;; to when you sit in a movie theater and only the images on the screen)
;; are visible.
;;
;; Frames are advanced manually to enable close control over how quickly the
;; video is displayed. This lets us simulate framerates to some extent.

;; NOTE: The field viewing-width specifies the degrees of visual angle for the
;; video's width. These value is for reproducing psychological studies and can
;; be set to whatever is appropriate. The availability of this information lets
;; us convert locations in the video in pixel coordinates into degree
;; coordinates, which are commonly used to describe distance across the retina
;; and eye movements.

(defrecord CameraTheater [video-path video-capture current-frame
                          viewing-width increment])

(defn width
  "Return the pixel width of the video."
  [ct]
  (let [^VideoCapture vc (:video-capture ct)]
    (.get vc Videoio/CAP_PROP_FRAME_WIDTH)))

(defn height
  "Return the pixel height of the video."
  [ct]
  (let [^VideoCapture vc (:video-capture ct)]
    (.get vc Videoio/CAP_PROP_FRAME_HEIGHT)))

(defn poll-camera
  "Returns a copy of the current frame as an OpenCV matrix or nil if none exists."
  [ct]
  (when-let [^Mat cf @(:current-frame ct)]
    (.clone cf)))

(defn poll-location
  "Provides the location of the center of the current view."
  [ct]
  ;; the x and y coordinates are assumed to be 0.
  {:xpos (math/floor (/ (width ct) 2))
   :ypos (math/floor (/ (height ct) 2))
   :xcorner 0
   :ycorner 0})

(defn- advance-frame
  "Advance the video by one frame and update the current stored frame
  if requested."
  ([ct]
   (advance-frame ct true))
  ([ct update?]
   (let [^VideoCapture vc (:video-capture ct)]
     (when (and (.isOpened vc) (.grab vc) update?)
       (let [img (Mat.)]
         (.retrieve vc img)
         (reset! (:current-frame ct) img))))))

(defn advance-frames
  "Advance the video by n frames, storing the final one. Assumes n > 0."
  [ct n]
  (dotimes [i (- n 1)] (advance-frame ct false))
  (advance-frame ct true))

;; releases the video file associated with this camera and
;; sets the current frame to nil.
(defn release
  "Release the handle to the video file and set the current frame to nil."
  [ct]
  (reset! (:current-frame ct) nil)
  (when (.isOpened (:video-capture ct))
    (.release (:video-capture ct))))

(defn reset
  "Reopen the video file from the beginning."
  [ct]
  (release ct)
  (.open (:video-capture ct) (:video-path ct)))

(defn create-theater-camera
  ([video-path]
   (create-theater-camera video-path nil))
  ([video-path additional-args]
   (let [camera
         (CameraTheater. video-path (VideoCapture. video-path) (atom nil)
                         (or (:viewing-width additional-args) viewing-width)
                         (or (:increment additional-args) increment))
         skip-frames (or (:skip-frames additional-args) skip-frames)]
     (when (> skip-frames 0)
       (advance-frames camera skip-frames))

     camera)))
