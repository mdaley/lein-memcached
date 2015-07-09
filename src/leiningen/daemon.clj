(ns leiningen.daemon
  (:import [com.thimbleware.jmemcached
            MemCacheDaemon
            Cache
            CacheElement
            LocalCacheElement
            Key]
           [com.thimbleware.jmemcached.storage CacheStorage]
           [com.thimbleware.jmemcached.protocol
            Op
            CommandMessage
            MemcachedCommandHandler
            ResponseMessage]
           [com.thimbleware.jmemcached.protocol.binary
            MemcachedBinaryPipelineFactory
            MemcachedBinaryCommandDecoder
            MemcachedBinaryCommandDecoder$BinaryOp
            MemcachedBinaryResponseEncoder
            MemcachedBinaryResponseEncoder$ResponseCode]
           [com.thimbleware.jmemcached.protocol.exceptions
            MalformedCommandException]
           [java.nio ByteBuffer ByteOrder]
           [org.jboss.netty.buffer
            ChannelBuffer
            ChannelBuffers]
           [org.jboss.netty.channel
            Channel
            Channels
            ChannelHandler
            ChannelHandlerContext
            MessageEvent]
           [org.jboss.netty.channel.group DefaultChannelGroup]))

(def spymemcache-cmd-type
  #{Op/ADD Op/SET Op/REPLACE Op/APPEND Op/PREPEND})

(def cmd-type-inc-decr
  #{Op/INCR Op/DECR})

(defn get-binary-op
  [opcode]
  (if (= opcode 29)
    MemcachedBinaryCommandDecoder$BinaryOp/Get
    (if (< opcode (count (MemcachedBinaryCommandDecoder$BinaryOp/values)))
      (get (MemcachedBinaryCommandDecoder$BinaryOp/values) opcode)
      (throw (RuntimeException. "Unsupported Operation")))))

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

(defn binary-encoder
  []
  (proxy [MemcachedBinaryResponseEncoder] []
    (getStatusCode [^ResponseMessage command]
      (if (and (= Op/GET (.op (.cmd command)))
               (nil? (first (.elements command))))
        MemcachedBinaryResponseEncoder$ResponseCode/KEYNF
        (proxy-super getStatusCode command)))))

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
                                   (binary-encoder)])))))

(defn create-daemon []
  (proxy [MemCacheDaemon] []
    (createMemcachedBinaryPipelineFactory
      [^Cache cache
       ^String memcachedVersion
       ^Boolean verbose
       ^Integer idleTime
       ^DefaultChannelGroup allChannels]
      (binary-pipeline cache memcachedVersion verbose idleTime allChannels))))
