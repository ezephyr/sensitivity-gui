(ns sensitivity.io
  (:use [gloss.core :only [defcodec ordered-map repeated]]
        [gloss.io   :only [decode]])
  (:import java.io.RandomAccessFile
           java.nio.ByteBuffer
           java.nio.ByteOrder))


(def BUFFER-SIZE 640)

(def MAGIC-HEADER 0x711ad917)

(def OFFSETS [0x1ff800
              0x1ffc00])

(defcodec sensor-data
  (ordered-map :magic-header  :uint32
               :cap-idx       :uint32
               :image-idx     :uint32
               :img-chksum    :uint32
               :img-timestamp :uint32
               :entries (repeated [:uint32
                                   :float32 :float32 :float32
                                   :float32 :float32 :float32 ]
                                  :prefix :byte)
               :padding (repeated :byte :prefix :none)))

(defn open-channel [file]
  (.getChannel (RandomAccessFile. file "r")))

(defn channel->bb [channel offset]
  (.order
   (.map channel
         java.nio.channels.FileChannel$MapMode/READ_ONLY offset BUFFER-SIZE)
   ByteOrder/BIG_ENDIAN))

(defn read-data-at-offset [channel offset]
  (dissoc (decode sensor-data
                  (channel->bb channel offset))
          :padding))

(defn read-data-from-file [file]
  (with-open [chn (open-channel file)]
    (doall
     (map (partial read-data-at-offset chn)
          OFFSETS))))

