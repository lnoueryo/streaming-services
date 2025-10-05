package com.example.streamingservices

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreviewX
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.streamingservices.ui.theme.StreamingServicesTheme
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainScreen()
        }
    }
}
val HOST = "192.168.11.16"
//val HOST = "10.0.2.2"
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("準備OK\n") }
    val eglBase = remember { EglBase.create() }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ローカル映像のプレビュー
        localVideoTrack?.let { track ->
            LocalVideoView(track, eglBase) // weight は LocalVideoView 内で設定
        }

        Text(
            text = logText,
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
                .statusBarsPadding()
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    // 変更：eglBase と ローカルVideoTrack受け取りコールバックを渡す
                    startSignaling(
                        context = context,
                        eglBase = eglBase,
                        onLocalVideo = { track -> localVideoTrack = track },
                        onLog = { msg -> logText += "$msg\n" }
                    )
                },
                modifier = Modifier
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) {
                Text("Offer送信 → Answer受信テスト")
            }
        }
    }
}

@Composable
fun CameraPreview(onClose: () -> Unit) {
    val context = LocalContext.current

    // ✅ 1. mutableStateOfを正しく使用
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // ✅ 2. 権限リクエストランチャー
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // ✅ 3. 権限チェック
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    // ✅ 4. カメラプレビューの描画
    if (hasPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = CameraPreviewX.Builder().build()
                        val selector = CameraSelector.DEFAULT_BACK_CAMERA

                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            ctx as ComponentActivity,
                            selector,
                            preview
                        )
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // 閉じるボタン
            Button(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) {
                Text("✕")
            }
        }
    } else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("カメラの権限が必要です")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen()
}

@Composable
fun ColumnScope.LocalVideoView(videoTrack: VideoTrack, eglBase: EglBase) {
    AndroidView(
        factory = { ctx ->
            val renderer = SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setMirror(true)
            }
            videoTrack.addSink(renderer)
            renderer
        },
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f) // Column の子として有効
    )
}
var webSocket: WebSocket? = null

var pc: PeerConnection? = null

// Composable ではない関数
fun startSignaling(
    context: Context,
    eglBase: EglBase,
    onLocalVideo: (VideoTrack) -> Unit,
    onLog: (String) -> Unit
) {
    val mainHandler = Handler(Looper.getMainLooper())
    fun postLog(msg: String) = mainHandler.post { onLog(msg) }

    postLog("ボタン押された！")

    // ★ ここは引数の eglBase を使う（新しく作らない）
    val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
    val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
    )

    val factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()

    // ---- PeerConnection 作成（あなたの既存コードそのまま） ----
    val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
    val config = PeerConnection.RTCConfiguration(iceServers)

    pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            postLog("Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            postLog("ICE connection state: $state")

            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    postLog("✅ P2P接続が確立しました！（ICE CONNECTED）")
                }
                PeerConnection.IceConnectionState.COMPLETED -> {
                    postLog("✅ ICE接続が完全に確立しました！（COMPLETED）")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    postLog("❌ ICE接続に失敗しました")
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            postLog("ICE receiving: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            postLog("ICE gathering state: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                val sdp = pc?.localDescription
                if (sdp != null) {
                    val offerJson = Gson().toJson(
                        mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)
                    )
                    webSocket?.send(offerJson)
                    postLog("ICE完了後にOffer送信")
                }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            postLog("New ICE candidate: $candidate")
            if (candidate != null) {
                val candidateJson = Gson().toJson(
                    mapOf(
                        "type" to "candidate",
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "candidate" to candidate.sdp
                    )
                )
                webSocket?.send(candidateJson)
                postLog("ICE Candidate送信: ${candidate.sdp.take(30)}...")
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            postLog("ICE candidates removed")
        }

        override fun onAddStream(stream: MediaStream?) {
            postLog("Stream added: ${stream?.id}")
        }

        override fun onRemoveStream(stream: MediaStream?) {
            postLog("Stream removed: ${stream?.id}")
        }

        override fun onDataChannel(channel: DataChannel?) {
            postLog("DataChannel opened: ${channel?.label()}")
        }

        override fun onRenegotiationNeeded() {
            postLog("Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            postLog("Track added: ${receiver?.track()?.id()}")
        }
    })

    if (pc == null) { postLog("PeerConnection の作成に失敗しました"); return } else postLog("PeerConnection 作成成功！")

    // ---- カメラ映像の VideoTrack 作成（あなたの既存コード）----
    val videoSource = factory.createVideoSource(false)
    val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
    val enumerator = Camera2Enumerator(context)

    val videoCapturer: CameraVideoCapturer? =
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let { enumerator.createCapturer(it, null) }
            ?: enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }?.let { enumerator.createCapturer(it, null) }

    if (videoCapturer == null) { postLog("カメラが見つかりません"); return }

    videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
    videoCapturer.startCapture(1280, 720, 30)

    val videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)

    // ★ ここが重要：UI に渡す
    onLocalVideo(videoTrack)

    // ---- 音声トラック（あなたの既存コード）----
    val audioSource = factory.createAudioSource(MediaConstraints())
    val audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)

    // ---- PeerConnection にトラック追加 ----
    pc?.addTrack(videoTrack)
    pc?.addTrack(audioTrack)

    // WebSocket 接続開始
    val client = OkHttpClient()
    val request = Request.Builder().url("ws://$HOST:8080/ws").build()
    webSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            postLog("WebSocket接続成功")

            // 接続成功後に Offer を作る
            val constraints = MediaConstraints()
            pc?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    postLog("Offer作成成功")
                    pc?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            val offerJson = Gson().toJson(
                                mapOf("type" to "offer", "sdp" to desc.description)
                            )
                            webSocket?.send(offerJson)
                            postLog("Offer送信: ${desc.description.take(30)}...")
                        }
                        override fun onSetFailure(error: String?) { postLog("setLocalDescription失敗: $error") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, desc)
                }

                override fun onCreateFailure(error: String) {
                    postLog("Offer作成失敗: $error")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)
        }

        override fun onMessage(ws: WebSocket, text: String) {
//            postLog("サーバーから受信: $text")
            try {
                val json = Gson().fromJson(text, Map::class.java)
                when (json["type"]) {
                    "answer" -> {
                        val sdp = json["sdp"] as String
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        pc?.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() { postLog("Answer受信＆セット成功") }
                            override fun onSetFailure(p0: String?) { postLog("Answerセット失敗: $p0") }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, answer)
                    }
                    "candidate" -> {
                        val candidate = IceCandidate(
                            json["sdpMid"] as String,
                            (json["sdpMLineIndex"] as Double).toInt(),
                            json["candidate"] as String
                        )
                        pc?.addIceCandidate(candidate)
                        postLog("ICE Candidate追加: ${candidate.sdp.take(30)}...")
                    }
                }
            } catch (e: Exception) {
                postLog("受信エラー: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            postLog("WebSocketエラー: ${t.message}")
        }
    })
}
