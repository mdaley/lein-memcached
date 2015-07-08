(ns leiningen.memcached
  (:require [leiningen.core.main :as main])
  (:import [com.thimbleware.jmemcached
            MemCacheDaemon
            CacheImpl
            Cache
            CacheElement
            LocalCacheElement
            Key]
           [com.thimbleware.jmemcached.storage CacheStorage]
           [com.thimbleware.jmemcached.storage.hash ConcurrentLinkedHashMap
            ConcurrentLinkedHashMap$EvictionPolicy]
           [com.thimbleware.jmemcached.protocol
            Op
            CommandMessage
            MemcachedCommandHandler]
           [com.thimbleware.jmemcached.protocol.binary
            MemcachedBinaryPipelineFactory
            MemcachedBinaryCommandDecoder
            MemcachedBinaryCommandDecoder$BinaryOp
            MemcachedBinaryResponseEncoder]
           [com.thimbleware.jmemcached.protocol.exceptions
            MalformedCommandException]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer ByteOrder]
           [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers]
           [org.jboss.netty.channel
            Channel
            Channels
            ChannelHandler
            ChannelHandlerContext]
           [org.jboss.netty.channel.group DefaultChannelGroup]))

(def running (ref false))

(def spymemcache-cmd-type
  #{Op/ADD Op/SET Op/REPLACE Op/APPEND Op/PREPEND})

(def cmd-type-inc-decr
  #{Op/INCR Op/DECR})

(defn- config-value
  "Get a value from project config or, optionally, use a default value."
  [project k & [default]]
  (get (project :memcached) k default))

(defn- create-store
  "Create a store for memcached to use."
  [max-items max-bytes]
  (ConcurrentLinkedHashMap/create ConcurrentLinkedHashMap$EvictionPolicy/FIFO max-items max-bytes))

(defn get-binary-op
  [opcode]
  (if (= opcode 29)
    MemcachedBinaryCommandDecoder$BinaryOp/Get
    (get (MemcachedBinaryCommandDecoder$BinaryOp/values) opcode)))

(defn binary-decoder
  []
  (proxy [MemcachedBinaryCommandDecoder] []
    (decode [^ChannelHandlerContext chc ^Channel channel ^ChannelBuffer cb]
      (when (>= (.readableBytes cb) 24)
        (.markReaderIndex cb)
        (let [hb (ChannelBuffers/buffer ByteOrder/BIG_ENDIAN 24)
              _ (.readBytes cb (cast ChannelBuffer  hb))
              magic (.readUnsignedByte hb)]
          (when (not= magic 0x80)
            (.resetReaderIndex hb)
            (throw (MalformedCommandException. "Binary request payload is invalid, magic byte incorrect")))
          (let [opcode (.readUnsignedByte hb)
                key-length (.readShort hb)
                extra-length (.readUnsignedByte hb)
                data-type (.readUnsignedByte hb)
                reserved (.readShort hb)
                total-body-length (.readInt hb)
                opaque (.readInt hb)
                cas (.readLong hb)]
            (if (< (.readableBytes cb) total-body-length)
              (.resetReaderIndex cb)
              (let [bcmd (get-binary-op opcode)
                    cmd-type (.correspondingOp bcmd)
                    cmd-message (CommandMessage/command cmd-type)]
                (set! (.noreply cmd-message) (.noreply bcmd))
                (set! (.cas_key cmd-message) cas)
                (set! (.opaque cmd-message) opaque)
                (set! (.addKeyToResponse cmd-message) (.addKeyToResponse bcmd))
                (let [extra-buffer (ChannelBuffers/buffer ByteOrder/BIG_ENDIAN extra-length)]
                  (.readBytes cb extra-buffer)
                  (if (not= key-length 0)
                    (let [key-buffer (ChannelBuffers/buffer ByteOrder/BIG_ENDIAN key-length)]
                      (.readBytes cb key-buffer)
                      (set! (.keys cmd-message) (list (Key. (.copy key-buffer))))
                      (println cmd-type)
                      (if (contains? spymemcache-cmd-type cmd-type)
                        (let [expire (if (not= (.capacity extra-buffer) 0)
                                       (.readUnsignedShort extra-buffer)
                                       0)
                              expire-c (if (and (not= 0 expire) (< expire CacheElement/THIRTY_DAYS))
                                         (+ expire (LocalCacheElement/Now))
                                         expire)
                              flags (if (not= (.capacity extra-buffer) 0)
                                      (.readUnsignedShort extra-buffer)
                                      0)
                              size (- total-body-length key-length extra-length)
                              element (LocalCacheElement. (Key. (.slice key-buffer))
                                                          flags
                                                          expire-c
                                                          0)
                              data (ChannelBuffers/buffer size)]
                          (set! (.element cmd-message) element)
                          (.readBytes cb data)
                          (.setData (.element cmd-message) data)
                          cmd-message)
                        (if (contains? cmd-type-inc-decr cmd-type)
                          (let [inital-value (.readUnsignedInt extra-buffer)
                                amount (.readUnsignedInt extra-buffer)
                                expiration (.readUnsignedInt extra-buffer)]
                            (set! (.incrAmount cmd-message) amount)
                            (set! (.incrExpiry cmd-message) expiration)
                            cmd-message)
                          cmd-message)))
                    cmd-message))))))))))

(defn binary-pipeline
  [^Cache cache
   ^String memcachedVersion
   ^Boolean verbose
   ^Integer idleTime
   ^DefaultChannelGroup allChannels]
  (proxy [MemcachedBinaryPipelineFactory] [cache
                                           memcachedVersion
                                           verbose
                                           idleTime
                                           allChannels]
    (getPipeline []
      (Channels/pipeline
       (into-array ChannelHandler [(binary-decoder)
                                   (MemcachedCommandHandler. cache memcachedVersion verbose idleTime allChannels)
                                   (MemcachedBinaryResponseEncoder.)])))))

(def cache-deamon
  (proxy [MemCacheDaemon] []
    (createMemcachedBinaryPipelineFactory
      [^Cache cache
       ^String memcachedVersion
       ^Boolean verbose
       ^Integer idleTime
       ^DefaultChannelGroup allChannels]
      (binary-pipeline cache memcachedVersion verbose idleTime allChannels))))

(defn- create-instance
  "Create an instance of the memcached daemon."
  [port max-items max-bytes verbose binary]
  (doto cache-deamon
    (.setCache (CacheImpl. (create-store max-items max-bytes)))
    (.setAddr (InetSocketAddress. port))
    (.setVerbose verbose)
    (.setBinary binary)))

(defn start-memcached
  [project]
  (if (not @running)
    (dosync
     (let [port (config-value project :port 11211)
           max-items (config-value project :max-items 100)
           max-bytes (config-value project :max-bytes 100000)
           verbose (config-value project :verbose false)
           binary (config-value project :binary false)]
       (println (str "lein-memcached: starting in-memory memcached instance on port " port "."))
       (.start (create-instance port max-items max-bytes verbose binary))
       (ref-set running true)))
    (throw (RuntimeException. "Already running"))))

(defn stop-memcached
  []
  (dosync
   (.stop cache-deamon)
   (ref-set running false)))

(defn memcached
  "Run memcached in-memory."
  [project & args]
  (start-memcached project)
  (if (seq args)
    (try
      (main/apply-task (first args) project (rest args))
      (finally (.stop cache-deamon)))
    (while running (Thread/sleep 500))))
