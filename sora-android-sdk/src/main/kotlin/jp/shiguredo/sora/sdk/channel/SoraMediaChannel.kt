package jp.shiguredo.sora.sdk.channel

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import jp.shiguredo.sora.sdk.BuildConfig
import jp.shiguredo.sora.sdk.channel.data.ChannelAttendeesCount
import jp.shiguredo.sora.sdk.channel.option.PeerConnectionOption
import jp.shiguredo.sora.sdk.channel.option.SoraMediaOption
import jp.shiguredo.sora.sdk.channel.rtc.PeerChannel
import jp.shiguredo.sora.sdk.channel.rtc.PeerChannelImpl
import jp.shiguredo.sora.sdk.channel.rtc.PeerNetworkConfig
import jp.shiguredo.sora.sdk.channel.signaling.SignalingChannel
import jp.shiguredo.sora.sdk.channel.signaling.SignalingChannelImpl
import jp.shiguredo.sora.sdk.channel.signaling.message.MessageConverter
import jp.shiguredo.sora.sdk.channel.signaling.message.NotificationMessage
import jp.shiguredo.sora.sdk.channel.signaling.message.OfferConfig
import jp.shiguredo.sora.sdk.channel.signaling.message.OfferMessage
import jp.shiguredo.sora.sdk.channel.signaling.message.PushMessage
import jp.shiguredo.sora.sdk.channel.signaling.message.SwitchedMessage
import jp.shiguredo.sora.sdk.error.SoraDisconnectReason
import jp.shiguredo.sora.sdk.error.SoraErrorReason
import jp.shiguredo.sora.sdk.util.ReusableCompositeDisposable
import jp.shiguredo.sora.sdk.util.SoraLogger
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.SessionDescription
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/**
 * Sora への接続を行うクラスです.
 * [SignalingChannel] と [PeerChannel] の管理、協調動作制御を行っています.
 * このクラスを利用することでシグナリングの詳細が隠蔽され、単一の [Listener] でイベントを受けることが出来ます.
 *
 *
 * シグナリングの手順とデータに関しては Sora のドキュメント
 *   [https://sora.shiguredo.jp/doc/SIGNALING.html](https://sora.shiguredo.jp/doc/SIGNALING.html)を参照ください.
 *
 * @constructor
 * SoraMediaChannel インスタンスを生成します.
 *
 * @param context `android.content.Context`
 * @param signalingEndpoint シグナリングの URL
 * @param signalingEndpointCandidates シグナリングの URL (クラスター機能で複数の URL を利用したい場合はこちらを指定する)
 * @param signalingMetadata connect メッセージに含める `metadata`
 * @param channelId Sora に接続するためのチャネル ID
 * @param mediaOption 映像、音声に関するオプション
 * @param timeoutSeconds WebSocket の接続タイムアウト (秒)
 * @param listener イベントリスナー
 * @param clientId connect メッセージに含める `client_id`
 * @param signalingNotifyMetadata connect メッセージに含める `signaling_notify_metadata`
 * @param dataChannelSignaling connect メッセージに含める `data_channel_signaling`
 * @param ignoreDisconnectWebSocket connect メッセージに含める `ignore_disconnect_websocket`
 */
class SoraMediaChannel @JvmOverloads constructor(
    private val context: Context,
    private val signalingEndpoint: String? = null,
    private val signalingEndpointCandidates: List<String> = emptyList(),
    private val channelId: String,
    private val signalingMetadata: Any? = "",
    private val mediaOption: SoraMediaOption,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    private var listener: Listener?,
    private val clientId: String? = null,
    private val signalingNotifyMetadata: Any? = null,
    private val peerConnectionOption: PeerConnectionOption = PeerConnectionOption(),
    dataChannelSignaling: Boolean? = null,
    ignoreDisconnectWebSocket: Boolean? = null
) {
    companion object {
        private val TAG = SoraMediaChannel::class.simpleName

        const val DEFAULT_TIMEOUT_SECONDS = 10L
    }

    // connect メッセージに含める `data_channel_signaling`
    private val connectDataChannelSignaling: Boolean?

    // connect メッセージに含める `ignore_disconnect_websocket`
    private val connectIgnoreDisconnectWebSocket: Boolean?

    // DataChannel 経由のシグナリングが有効なら true
    // Sora から渡された値 (= offer メッセージ) を参照して更新している
    private var offerDataChannelSignaling: Boolean = false

    // DataChannel 経由のシグナリング利用時に WebSocket の切断を無視するなら true
    // 同じく switched メッセージを参照して更新している
    private var switchedIgnoreDisconnectWebSocket: Boolean = false

    init {
        if ((signalingEndpoint == null && signalingEndpointCandidates.isEmpty()) ||
            (signalingEndpoint != null && signalingEndpointCandidates.isNotEmpty())
        ) {
            throw IllegalArgumentException("Either signalingEndpoint or signalingEndpointCandidates must be specified")
        }

        // コンストラクタ以外で dataChannelSignaling, ignoreDisconnectWebSocket を参照すべきではない
        // 各種ロジックの判定には Sora のメッセージに含まれる値を参照する必要があるため、以下を利用するのが正しい
        // - offerDataChannelSignaling
        // - switchedIgnoreDisconnectWebSocket
        connectDataChannelSignaling = dataChannelSignaling
        connectIgnoreDisconnectWebSocket = ignoreDisconnectWebSocket
    }

    /**
     * ロール
     */
    val role = mediaOption.requiredRole

    private var getStatsTimer: Timer? = null
    private var dataChannels: MutableMap<String, DataChannel> = mutableMapOf()

    /**
     * [SoraMediaChannel] からコールバックイベントを受けるリスナー
     */
    interface Listener {
        /**
         * ローカルストリームが追加されたときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.MediaStream`
         * - `org.webrtc.MediaStream.videoTracks`
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param ms 追加されたメディアストリーム
         */
        fun onAddLocalStream(mediaChannel: SoraMediaChannel, ms: MediaStream) {}

        /**
         * リモートストリームが追加されたときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.MediaStream`
         * - `org.webrtc.MediaStream.videoTracks`
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param ms 追加されたメディアストリーム
         */
        fun onAddRemoteStream(mediaChannel: SoraMediaChannel, ms: MediaStream) {}

        /**
         * リモートストリームが削除されたときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.MediaStream.label()`
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param label メディアストリームのラベル (`ms.label()`)
         */
        fun onRemoveRemoteStream(mediaChannel: SoraMediaChannel, label: String) {}

        /**
         * Sora との接続が確立されたときに呼び出されるコールバック.
         *
         * cf.
         * - [PeerChannel]
         *
         * @param mediaChannel イベントが発生したチャネル
         */
        fun onConnect(mediaChannel: SoraMediaChannel) {}

        /**
         * Sora との接続が切断されたときに呼び出されるコールバック.
         *
         * cf.
         * - [PeerChannel]
         *
         * @param mediaChannel イベントが発生したチャネル
         */
        fun onClose(mediaChannel: SoraMediaChannel) {}

        /**
         * Sora との通信やメディアでエラーが発生したときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.PeerConnection`
         * - `org.webrtc.PeerConnection.Observer`
         * - [PeerChannel]
         *
         * @param reason エラーの理由
         */
        fun onError(mediaChannel: SoraMediaChannel, reason: SoraErrorReason) {}

        /**
         * Sora との通信やメディアでエラーが発生したときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.PeerConnection`
         * - `org.webrtc.PeerConnection.Observer`
         * - [PeerChannel]
         *
         * @param reason エラーの理由
         * @param message エラーの情報
         */
        fun onError(mediaChannel: SoraMediaChannel, reason: SoraErrorReason, message: String) {}

        /**
         * Sora との通信やメディアで警告が発生したときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.PeerConnection`
         * - `org.webrtc.PeerConnection.Observer`
         * - [PeerChannel]
         *
         * @param reason 警告の理由
         */
        fun onWarning(mediaChannel: SoraMediaChannel, reason: SoraErrorReason) {}

        /**
         * Sora との通信やメディアで警告が発生したときに呼び出されるコールバック.
         *
         * cf.
         * - `org.webrtc.PeerConnection`
         * - `org.webrtc.PeerConnection.Observer`
         * - [PeerChannel]
         *
         * @param reason 警告の理由
         * @param message 警告の情報
         */
        fun onWarning(mediaChannel: SoraMediaChannel, reason: SoraErrorReason, message: String) {}

        /**
         * 接続しているチャネルの参加者が増減したときに呼び出されるコールバック.
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param attendees 配信者数と視聴者数を含むオブジェクト
         */
        fun onAttendeesCountUpdated(mediaChannel: SoraMediaChannel, attendees: ChannelAttendeesCount) {}

        /**
         * Sora のシグナリング通知機能の通知を受信したときに呼び出されるコールバック.
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param notification プッシュ API により受信したメッセージ
         */
        fun onNotificationMessage(mediaChannel: SoraMediaChannel, notification: NotificationMessage) {}

        /**
         * Sora のプッシュ API によりメッセージを受信したときに呼び出されるコールバック.
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param push プッシュ API により受信したメッセージ
         */
        fun onPushMessage(mediaChannel: SoraMediaChannel, push: PushMessage) {}

        /**
         * PeerConnection の getStats() 統計情報を取得したときに呼び出されるコールバック.
         *
         * cf.
         * - https://w3c.github.io/webrtc-stats/
         * - https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/getStats
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param statsReport 統計レポート
         */
        fun onPeerConnectionStatsReady(mediaChannel: SoraMediaChannel, statsReport: RTCStatsReport) {}

        /**
         * サイマルキャスト配信のエンコーダ設定を変更するためのコールバック.
         *
         * 引数の encodings は Sora が送ってきた設定を反映した RtpParameters.Encoding のリストです.
         * デフォルトの実装ではなにも行いません.
         * このコールバックを実装し、引数のオブジェクトを変更することで、アプリケーションの要件に従った
         * 設定をエンコーダにわたすことができます.
         *
         * cf. Web 標準の対応 API は次のとおりです. libwebrtc の native(C++) と android の実装は
         * 異なりますので注意してください.
         * - https://w3c.github.io/webrtc-pc/#dom-rtcrtpencodingparameters
         * - https://w3c.github.io/webrtc-pc/#dom-rtcrtpsender-setparameters
         *
         * @param mediaChannel イベントが発生したチャネル
         * @param encodings Sora から送信された encodings
         */
        fun onSenderEncodings(mediaChannel: SoraMediaChannel, encodings: List<RtpParameters.Encoding>) {}
    }

    private var peer: PeerChannel? = null
    private var signaling: SignalingChannel? = null

    private var switchedToDataChannel = false
    private var closing = false

    // type: redirect で再利用するために、初回接続時の clientOffer を保持する
    private var clientOffer: SessionDescription? = null

    /**
     * コネクション ID.
     */
    var connectionId: String? = null
        private set

    /**
     * シグナリングに利用しているエンドポイント.
     */
    var connectedSignalingEndpoint: String? = null
        private set

    private val compositeDisposable = ReusableCompositeDisposable()

    private val signalingListener = object : SignalingChannel.Listener {

        override fun onDisconnect(disconnectReason: SoraDisconnectReason?) {
            SoraLogger.d(
                TAG,
                "[channel:$role] @signaling:onDisconnect " +
                    "switchedToDataChannel=$switchedToDataChannel, " +
                    "switchedIgnoreDisconnectWebSocket=$switchedIgnoreDisconnectWebSocket"

            )
            if (switchedToDataChannel && switchedIgnoreDisconnectWebSocket) {
                // なにもしない
                SoraLogger.d(TAG, "[channel:$role] @signaling:onDisconnect: IGNORE")
            } else {
                internalDisconnect(disconnectReason)
            }
        }

        override fun onConnect(connectedEndpoint: String) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onOpen")
            connectedSignalingEndpoint = connectedEndpoint
        }

        override fun onInitialOffer(offerMessage: OfferMessage) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onInitialOffer")
            this@SoraMediaChannel.connectionId = offerMessage.connectionId
            handleInitialOffer(offerMessage)
        }

        override fun onSwitched(switchedMessage: SwitchedMessage) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onSwitched")
            switchedIgnoreDisconnectWebSocket = switchedMessage.ignoreDisconnectWebsocket ?: false
            handleSwitched(switchedMessage)
        }

        override fun onUpdatedOffer(sdp: String) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onUpdatedOffer")
            handleUpdateOffer(sdp)
        }

        override fun onReOffer(sdp: String) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onReOffer")
            handleReOffer(sdp)
        }

        override fun onNotificationMessage(notification: NotificationMessage) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onNotificationMessage")
            handleNotificationMessage(notification)
        }

        override fun onPushMessage(push: PushMessage) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onPushMessage")
            listener?.onPushMessage(this@SoraMediaChannel, push)
        }

        override fun onError(reason: SoraErrorReason) {
            SoraLogger.d(TAG, "[channel:$role] @signaling:onError:$reason")
            val ignoreError = switchedIgnoreDisconnectWebSocket
            if (switchedToDataChannel && ignoreError) {
                // なにもしない
                SoraLogger.d(TAG, "[channel:$role] @signaling:onError: IGNORE reason=$reason")
            } else {
                listener?.onError(this@SoraMediaChannel, reason)
            }
        }

        override fun getStats(handler: (RTCStatsReport?) -> Unit) {
            if (peer != null) {
                peer!!.getStats(handler)
            } else {
                handler(null)
            }
        }

        override fun onRedirect(location: String) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onRedirect")

            SoraLogger.i(TAG, "[channel:$role] closing old SignalingChannel")
            signaling?.disconnect(null)

            SoraLogger.i(TAG, "[channel:$role] opening new SignalingChannel")
            val handler = Handler(Looper.getMainLooper())
            handler.post() {
                connectSignalingChannel(clientOffer, location)
            }
        }
    }

    private val peerListener = object : PeerChannel.Listener {

        override fun onLocalIceCandidateFound(candidate: IceCandidate) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onLocalIceCandidateFound")
            signaling?.sendCandidate(candidate.sdp)
        }

        override fun onRemoveRemoteStream(label: String) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onRemoveRemoteStream:$label")
            if (connectionId != null && label == connectionId) {
                SoraLogger.d(TAG, "[channel:$role] this stream is mine, ignore")
                return
            }
            listener?.onRemoveRemoteStream(this@SoraMediaChannel, label)
        }

        override fun onAddRemoteStream(ms: MediaStream) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onAddRemoteStream msid=:${ms.id}, connectionId=$connectionId")
            if (mediaOption.multistreamEnabled && connectionId != null && ms.id == connectionId) {
                SoraLogger.d(TAG, "[channel:$role] this stream is mine, ignore: ${ms.id}")
                return
            }
            listener?.onAddRemoteStream(this@SoraMediaChannel, ms)
        }

        override fun onAddLocalStream(ms: MediaStream) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onAddLocalStream")
            listener?.onAddLocalStream(this@SoraMediaChannel, ms)
        }

        override fun onConnect() {
            SoraLogger.d(TAG, "[channel:$role] @peer:onConnect")
            stopTimer()
            listener?.onConnect(this@SoraMediaChannel)
        }

        override fun onDataChannelOpen(label: String, dataChannel: DataChannel) {
            dataChannels[label] = dataChannel
        }

        override fun onDataChannelMessage(label: String, dataChannel: DataChannel, messageData: String) {

            val expectedType = when (label) {
                "signaling" -> "re-offer"
                "notify" -> "notify"
                "push" -> "push"
                "stats" -> "req-stats"
                "e2ee" -> "NOT-IMPLEMENTED"
                else -> label // 追加が発生した時に備えて許容する
            }

            MessageConverter.parseType(messageData)?.let { type ->
                when (type) {
                    expectedType -> {
                        when (label) {
                            "signaling" -> {
                                val reOfferMessage = MessageConverter.parseReOfferMessage(messageData)
                                handleReOfferViaDataChannel(dataChannel, reOfferMessage.sdp)
                            }
                            "notify" -> {
                                val notificationMessage = MessageConverter.parseNotificationMessage(messageData)
                                handleNotificationMessage(notificationMessage)
                            }
                            "push" -> {
                                val pushMessage = MessageConverter.parsePushMessage(messageData)
                                listener?.onPushMessage(this@SoraMediaChannel, pushMessage)
                            }
                            "stats" -> {
                                // req-stats は type しかないので parse しない
                                handleReqStats(dataChannel)
                            }
                            "e2ee" -> {
                                SoraLogger.i(TAG, "NOT IMPLEMENTED: label=$label, type=$type, message=$messageData")
                            }
                            else ->
                                SoraLogger.i(TAG, "Unknown label: label=$label, type=$type, message=$messageData")
                        }
                    }
                    else -> SoraLogger.i(TAG, "Unknown type: label=$label, type=$type, message=$messageData")
                }
            }
        }

        override fun onDataChannelClosed(label: String, dataChannel: DataChannel) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onDataChannelClosed label=$label")

            // DataChannel が閉じられたが、その理由を知る方法がないため reason は null にする
            internalDisconnect(null)
        }

        override fun onSenderEncodings(encodings: List<RtpParameters.Encoding>) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onSenderEncodings")
            listener?.onSenderEncodings(this@SoraMediaChannel, encodings)
        }

        override fun onError(reason: SoraErrorReason) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onError:$reason")
            listener?.onError(this@SoraMediaChannel, reason)
        }

        override fun onError(reason: SoraErrorReason, message: String) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onError:$reason:$message")
            listener?.onError(this@SoraMediaChannel, reason, message)
        }

        override fun onWarning(reason: SoraErrorReason) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onWarning:$reason")
            listener?.onWarning(this@SoraMediaChannel, reason)
        }

        override fun onWarning(reason: SoraErrorReason, message: String) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onWarning:$reason:$message")
            listener?.onWarning(this@SoraMediaChannel, reason, message)
        }

        override fun onDisconnect(disconnectReason: SoraDisconnectReason?) {
            SoraLogger.d(TAG, "[channel:$role] @peer:onDisconnect:$disconnectReason")

            internalDisconnect(disconnectReason)
        }
    }

    /**
     * Sora に接続します.
     *
     * アプリケーションで接続後の処理が必要な場合は [Listener.onConnect] で行います.
     */
    fun connect() {
        try {
            val kClass = Class.forName("org.webrtc.WebrtcBuildVersion")
            val webrtcBranch = kClass.getField("webrtc_branch").get(null)
            val webrtcCommit = kClass.getField("webrtc_commit").get(null)
            val maintVersion = kClass.getField("maint_version").get(null)
            val webrtcRevision = kClass.getField("webrtc_revision").get(null)
            val webrtcBuildVersion = listOf(webrtcBranch, webrtcCommit, maintVersion)
                .joinToString(separator = ".")
            SoraLogger.d(TAG, "libwebrtc version = $webrtcBuildVersion @ $webrtcRevision")
        } catch (e: ClassNotFoundException) {
            SoraLogger.d(TAG, "connect: libwebrtc other than Shiguredo build is used.")
        }

        SoraLogger.d(
            TAG,
            """connect: SoraMediaOption
            |requiredRole            = ${mediaOption.requiredRole}
            |upstreamIsRequired      = ${mediaOption.upstreamIsRequired}
            |downstreamIsRequired    = ${mediaOption.downstreamIsRequired}
            |multistreamEnabled      = ${mediaOption.multistreamEnabled}
            |audioIsRequired         = ${mediaOption.audioIsRequired}
            |audioUpstreamEnabled    = ${mediaOption.audioUpstreamEnabled}
            |audioDownstreamEnabled  = ${mediaOption.audioDownstreamEnabled}
            |audioCodec              = ${mediaOption.audioCodec}
            |audioBitRate            = ${mediaOption.audioBitrate}
            |audioSource             = ${mediaOption.audioOption.audioSource}
            |useStereoInput          = ${mediaOption.audioOption.useStereoInput}
            |useStereoOutput         = ${mediaOption.audioOption.useStereoOutput}
            |videoIsRequired         = ${mediaOption.videoIsRequired}
            |videoUpstreamEnabled    = ${mediaOption.videoUpstreamEnabled}
            |videoUpstreamContext    = ${mediaOption.videoUpstreamContext}
            |videoDownstreamEnabled  = ${mediaOption.videoDownstreamEnabled}
            |videoDownstreamContext  = ${mediaOption.videoDownstreamContext}
            |videoEncoderFactory     = ${mediaOption.videoEncoderFactory}
            |videoDecoderFactory     = ${mediaOption.videoDecoderFactory}
            |videoCodec              = ${mediaOption.videoCodec}
            |videoBitRate            = ${mediaOption.videoBitrate}
            |videoCapturer           = ${mediaOption.videoCapturer}
            |simulcastEnabled        = ${mediaOption.simulcastEnabled}
            |simulcastRid            = ${mediaOption.simulcastRid}
            |spotlightEnabled        = ${mediaOption.spotlightEnabled}
            |spotlightNumber         = ${mediaOption.spotlightOption?.spotlightNumber}
            |signalingMetadata       = ${this.signalingMetadata}
            |clientId                = ${this.clientId}
            |signalingNotifyMetadata = ${this.signalingNotifyMetadata}""".trimMargin()
        )

        if (closing) {
            return
        }
        startTimer()
        requestClientOfferSdp()
    }

    private var timer: Timer? = null

    private fun startTimer() {
        stopTimer()
        timer = Timer()
        timer!!.schedule(
            object : TimerTask() {
                override fun run() {
                    timer = null
                    onTimeout()
                }
            },
            timeoutSeconds * 1000
        )
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun onTimeout() {
        SoraLogger.d(TAG, "[channel:$role] @peer:onTimeout")
        listener?.onError(this, SoraErrorReason.TIMEOUT)

        // ここに来た場合、 Sora に接続出来ていない = disconnect メッセージを送信する必要がない
        // そのため、 reason は null で良い
        internalDisconnect(null)
    }

    private fun requestClientOfferSdp() {
        val mediaOption = SoraMediaOption().apply {
            enableVideoDownstream(null)
            enableAudioDownstream()
        }
        val clientOfferPeer = PeerChannelImpl(
            appContext = context,
            networkConfig = PeerNetworkConfig(
                serverConfig = OfferConfig(
                    iceServers = emptyList(),
                    iceTransportPolicy = ""
                ),
                mediaOption = mediaOption
            ),
            mediaOption = mediaOption,
            listener = null
        )
        clientOfferPeer.run {
            val subscription = requestClientOfferSdp()
                .observeOn(Schedulers.io())
                .subscribeBy(
                    onSuccess = {
                        SoraLogger.d(TAG, "[channel:$role] @peer:clientOfferSdp")
                        disconnect(null)

                        if (it.isFailure) {
                            SoraLogger.d(TAG, "[channel:$role] failed to create client offer SDP: ${it.exceptionOrNull()?.message}")
                        }
                        val handler = Handler(Looper.getMainLooper())
                        clientOffer = it.getOrNull()
                        handler.post() {
                            connectSignalingChannel(clientOffer)
                        }
                    },
                    onError = {
                        SoraLogger.w(
                            TAG,
                            "[channel:$role] failed request client offer SDP: ${it.message}"
                        )
                        disconnect(SoraDisconnectReason.SIGNALING_FAILURE)
                    }

                )
            compositeDisposable.add(subscription)
        }
    }

    private fun connectSignalingChannel(clientOfferSdp: SessionDescription?, redirectLocation: String? = null) {
        val endpoints = when {
            redirectLocation != null -> listOf(redirectLocation)
            signalingEndpointCandidates.isNotEmpty() -> signalingEndpointCandidates
            else -> listOf(signalingEndpoint!!)
        }

        signaling = SignalingChannelImpl(
            endpoints = endpoints,
            role = role,
            channelId = channelId,
            connectDataChannelSignaling = connectDataChannelSignaling,
            connectIgnoreDisconnectWebSocket = connectIgnoreDisconnectWebSocket,
            mediaOption = mediaOption,
            connectMetadata = signalingMetadata,
            listener = signalingListener,
            clientOfferSdp = clientOfferSdp,
            clientId = clientId,
            signalingNotifyMetadata = signalingNotifyMetadata,
            redirect = redirectLocation != null
        )
        signaling!!.connect()
    }

    private fun handleInitialOffer(offerMessage: OfferMessage) {
        SoraLogger.d(TAG, "[channel:$role] initial offer")

        SoraLogger.d(TAG, "[channel:$role] @peer:starting")
        peer = PeerChannelImpl(
            appContext = context,
            networkConfig = PeerNetworkConfig(
                serverConfig = offerMessage.config,
                mediaOption = mediaOption
            ),
            mediaOption = mediaOption,
            dataChannelConfigs = offerMessage.dataChannels,
            listener = peerListener
        )

        if (offerMessage.dataChannels?.isNotEmpty() == true) {
            offerDataChannelSignaling = true
        }

        if (0 < peerConnectionOption.getStatsIntervalMSec) {
            getStatsTimer = Timer()
            SoraLogger.d(TAG, "Schedule getStats with interval ${peerConnectionOption.getStatsIntervalMSec} [msec]")
            getStatsTimer?.schedule(0L, peerConnectionOption.getStatsIntervalMSec) {
                peer?.getStats(
                    RTCStatsCollectorCallback {
                        listener?.onPeerConnectionStatsReady(this@SoraMediaChannel, it)
                    }
                )
            }
        }
        peer?.run {
            val subscription = handleInitialRemoteOffer(offerMessage.sdp, offerMessage.mid, offerMessage.encodings)
                .observeOn(Schedulers.io())
                .subscribeBy(
                    onSuccess = {
                        SoraLogger.d(TAG, "[channel:$role] @peer:answer")
                        signaling?.sendAnswer(it.description)
                    },
                    onError = {
                        val msg = "[channel:$role] failure in handleInitialOffer: ${it.message}"
                        SoraLogger.w(TAG, msg)
                        disconnect(SoraDisconnectReason.SIGNALING_FAILURE)
                    }
                )
            compositeDisposable.add(subscription)
        }
    }

    private fun handleSwitched(switchedMessage: SwitchedMessage) {
        switchedToDataChannel = true
        switchedIgnoreDisconnectWebSocket = switchedMessage.ignoreDisconnectWebsocket ?: false
        val earlyCloseWebSocket = switchedIgnoreDisconnectWebSocket
        if (earlyCloseWebSocket) {
            signaling?.disconnect(null)
        }
    }

    private fun handleUpdateOffer(sdp: String) {
        peer?.run {
            val subscription = handleUpdatedRemoteOffer(sdp)
                .observeOn(Schedulers.io())
                .subscribeBy(
                    onSuccess = {
                        SoraLogger.d(TAG, "[channel:$role] @peer:about to send updated answer")
                        signaling?.sendUpdateAnswer(it.description)
                    },
                    onError = {
                        val msg = "[channel:$role] failed handle updated offer: ${it.message}"
                        SoraLogger.w(TAG, msg)
                        disconnect(SoraDisconnectReason.SIGNALING_FAILURE)
                    }
                )
            compositeDisposable.add(subscription)
        }
    }

    private fun handleReOffer(sdp: String) {
        peer?.run {
            val subscription = handleUpdatedRemoteOffer(sdp)
                .observeOn(Schedulers.io())
                .subscribeBy(
                    onSuccess = {
                        SoraLogger.d(TAG, "[channel:$role] @peer:about to send re-answer")
                        signaling?.sendReAnswer(it.description)
                    },
                    onError = {
                        val msg = "[channel:$role] failed handle re-offer: ${it.message}"
                        SoraLogger.w(TAG, msg)
                        disconnect(SoraDisconnectReason.SIGNALING_FAILURE)
                    }
                )
            compositeDisposable.add(subscription)
        }
    }

    private fun handleReOfferViaDataChannel(dataChannel: DataChannel, sdp: String) {
        peer?.run {
            val subscription = handleUpdatedRemoteOffer(sdp)
                .observeOn(Schedulers.io())
                .subscribeBy(
                    onSuccess = {
                        SoraLogger.d(TAG, "[channel:$role] @peer:about to send re-answer")
                        peer?.sendReAnswer(dataChannel, it.description)
                    },
                    onError = {
                        val msg = "[channel:$role] failed handle re-offer: ${it.message}"
                        SoraLogger.w(TAG, msg)
                        disconnect(SoraDisconnectReason.SIGNALING_FAILURE)
                    }
                )
            compositeDisposable.add(subscription)
        }
    }

    private fun handleReqStats(dataChannel: DataChannel) {
        peer?.getStats {
            it?.let { reports ->
                peer?.sendStats(dataChannel, reports)
            }
        }
    }

    private fun handleNotificationMessage(notification: NotificationMessage) {
        when (notification.eventType) {
            "connection.created", "connection.destroyed" -> {
                val attendees = ChannelAttendeesCount(
                    numberOfDownstreams = notification.numberOfDownstreamConnections ?: 0,
                    numberOfUpstreams = notification.numberOfUpstreamConnections ?: 0,
                    numberOfSendrecvConnections = notification.numberOfSendrecvConnections!!,
                    numberOfSendonlyConnections = notification.numberOfSendonlyConnections!!,
                    numberOfRecvonlyConnections = notification.numberOfRecvonlyConnections!!,
                )
                listener?.onAttendeesCountUpdated(this@SoraMediaChannel, attendees)
            }
        }
        listener?.onNotificationMessage(this@SoraMediaChannel, notification)
    }

    /**
     * Sora への接続を切断します.
     *
     * アプリケーションとして切断後の処理が必要な場合は [Listener.onClose] で行います.
     */
    fun disconnect() {
        // アプリケーションから切断された場合は NO-ERROR とする
        internalDisconnect(SoraDisconnectReason.NO_ERROR)
    }

    private fun internalDisconnect(disconnectReason: SoraDisconnectReason?) {
        if (closing)
            return
        closing = true

        stopTimer()
        disconnectReason?.let {
            sendDisconnectIfNeeded(it)
        }
        compositeDisposable.dispose()

        listener?.onClose(this)
        listener = null

        // アプリケーションで定義された切断処理を実行した後に connectedSignalingEndpoint を null にする
        connectedSignalingEndpoint = null

        // 既に type: disconnect を送信しているので、 disconnectReason は null で良い
        signaling?.disconnect(null)
        signaling = null

        getStatsTimer?.cancel()
        getStatsTimer = null

        // 既に type: disconnect を送信しているので、 disconnectReason は null で良い
        peer?.disconnect(null)
        peer = null
    }

    private fun sendDisconnectOverWebSocket(disconnectReason: SoraDisconnectReason) {
        signaling?.sendDisconnect(disconnectReason)
    }

    private fun sendDisconnectOverDataChannel(disconnectReason: SoraDisconnectReason) {
        dataChannels["signaling"]?.let {
            peer?.sendDisconnect(it, disconnectReason)
        }
    }

    private fun sendDisconnectIfNeeded(disconnectReason: SoraDisconnectReason) {
        val state = peer?.connectionState() ?: null
        SoraLogger.d(
            TAG,
            "[channel:$role] sendDisconnectIfNeeded switched=$switchedToDataChannel, " +
                "switchedIgnoreDisconnectWebSocket=$switchedIgnoreDisconnectWebSocket, " +
                "reason=$disconnectReason, PeerConnectionState=$state"
        )

        if (state == PeerConnection.PeerConnectionState.FAILED) {
            // この関数に到達した時点で PeerConnectionState が FAILED なのでメッセージの送信は不要
            return
        }

        when (disconnectReason) {
            SoraDisconnectReason.NO_ERROR -> {
                if (!offerDataChannelSignaling) {
                    // WebSocket のみ
                    sendDisconnectOverWebSocket(disconnectReason)
                } else {
                    // WebSocket と DataChannel / DataChannel のみ
                    if (!switchedToDataChannel) {
                        // type: switched 未受信
                        sendDisconnectOverWebSocket(disconnectReason)
                    } else {
                        // type: switched 受信済
                        sendDisconnectOverDataChannel(disconnectReason)
                    }
                }
            }

            SoraDisconnectReason.WEBSOCKET_ONCLOSE, SoraDisconnectReason.WEBSOCKET_ONERROR -> {
                if (switchedToDataChannel && !switchedIgnoreDisconnectWebSocket) {
                    sendDisconnectOverDataChannel(disconnectReason)
                }
            }

            SoraDisconnectReason.SIGNALING_FAILURE, SoraDisconnectReason.PEER_CONNECTION_STATE_FAILED -> {
                // メッセージの送信は不要
            }

            else -> {
                // SoraDisconnectReason のすべての条件が網羅されていて欲しい
                if (BuildConfig.DEBUG) {
                    throw Exception("when statement should be exhaustive.")
                }
                SoraLogger.i(TAG, "when statement should be exhaustive.")
            }
        }
    }
}

