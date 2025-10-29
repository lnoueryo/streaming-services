package com.example.streamingservices
// TODO まずAndroidのストリーミングがGoに送られているか確認→フロントに流れているか確認
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import org.json.JSONObject
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
val HOST = "192.168.11.47"
//val HOST = "10.0.2.2"
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("準備OK\n") }
    val scrollState = rememberScrollState()
    val eglBase = remember { EglBase.create() }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ローカル映像のプレビュー
        localVideoTrack?.let { track ->
            LocalVideoView(track, eglBase) // weight は LocalVideoView 内で設定
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(scrollState)
                .statusBarsPadding()
        ) {
            Text(
                text = logText,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }

        // ボタンエリア
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    startSignaling(
                        context = context,
                        eglBase = eglBase,
                        onLocalVideo = { track -> localVideoTrack = track },
                        onLog = { msg ->
                            logText += "$msg\n"
                        }
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

    postLog("Initializing PeerConnectionFactory…")

    // --- WebRTC Factory 初期化 ---
    val encoderFactory = DefaultVideoEncoderFactory(
        eglBase.eglBaseContext,
        /* enableIntelVp8Encoder */ true,
        /* enableH264HighProfile */ true
    )
    val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .createInitializationOptions()
    )

    val factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(encoderFactory)
        .setVideoDecoderFactory(decoderFactory)
        .createPeerConnectionFactory()

    // --- ICE Servers ---
    val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )
    val config = PeerConnection.RTCConfiguration(iceServers)

    // --- PeerConnection 作成 ---
    pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            postLog("Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            postLog("ICE connection: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            val json = JSONObject().apply {
                put("type", "candidate")
                put("data", JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }
            webSocket?.send(json.toString())
            postLog("📤 ICE Candidate sent")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            postLog("ICE gathering: $state")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            postLog("Track added: ${receiver?.track()?.kind()}")
        }

        override fun onDataChannel(channel: DataChannel?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
    })

    if (pc == null) {
        postLog("❌ PeerConnection creation failed")
        return
    }
    postLog("✅ PeerConnection created")

    // --- カメラ映像の準備 ---
    val videoSource = factory.createVideoSource(false)
    val surfaceTextureHelper =
        SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
    val enumerator = Camera2Enumerator(context)

    val videoCapturer: CameraVideoCapturer? = enumerator.deviceNames
        .firstOrNull { enumerator.isFrontFacing(it) }
        ?.let { enumerator.createCapturer(it, null) }
        ?: enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
            ?.let { enumerator.createCapturer(it, null) }

    if (videoCapturer == null) {
        postLog("❌ Camera not found")
        return
    }

    videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
    videoCapturer.startCapture(1280, 720, 30)

    val videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
    val audioSource = factory.createAudioSource(MediaConstraints())
    val audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)
    pc?.addTrack(videoTrack)
    pc?.addTrack(audioTrack)
    onLocalVideo(videoTrack)

    // --- WebSocket Signaling ---
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("ws://$HOST:8080/ws/live/1/${(1000000..9999999).random()}")
        .build()

    webSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            postLog("🌐 WebSocket connected")
            ws.send(JSONObject(mapOf("type" to "offer")).toString())
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                val type = json.optString("type")

                when (type) {
                    // サーバーから offer を受信したとき
                    "offer" -> {
                        val data = json.getJSONObject("data")
                        val sdp = data.getString("sdp")
                        val sdpType = data.optString("type", "offer")

                        val offer = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(sdpType),
                            sdp
                        )

                        pc?.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                postLog("✅ RemoteDescription set (offer)")
                                // Answer作成
                                pc?.createAnswer(object : SdpObserver {
                                    override fun onCreateSuccess(desc: SessionDescription) {
                                        pc?.setLocalDescription(object : SdpObserver {
                                            override fun onSetSuccess() {
                                                // Answer送信（HTML版と完全一致）
                                                val msg = JSONObject().apply {
                                                    put("type", "answer")
                                                    put("data", JSONObject().apply {
                                                        put("sdp", desc.description)
                                                    })
                                                }
                                                ws.send(msg.toString())
                                                postLog("📤 Answer sent")
                                            }

                                            override fun onSetFailure(error: String) {
                                                postLog("❌ setLocalDescription failed: $error")
                                            }

                                            override fun onCreateSuccess(p0: SessionDescription?) {}
                                            override fun onCreateFailure(p0: String?) {}
                                        }, desc)
                                    }

                                    override fun onCreateFailure(error: String) {
                                        postLog("❌ createAnswer failed: $error")
                                    }

                                    override fun onSetSuccess() {}
                                    override fun onSetFailure(p0: String?) {}
                                }, MediaConstraints())
                            }

                            override fun onSetFailure(error: String) {
                                postLog("❌ setRemoteDescription failed: $error")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, offer)
                    }

                    // ICE candidate を受信
                    "candidate" -> {
                        val data = json.getJSONObject("data")
                        val candidate = IceCandidate(
                            data.getString("sdpMid"),
                            data.getInt("sdpMLineIndex"),
                            data.getString("candidate")
                        )
                        pc?.addIceCandidate(candidate)
                        postLog("✅ Candidate added")
                    }

                    else -> postLog("⚠️ Unknown msg type: $type")
                }

            } catch (e: Exception) {
                postLog("❌ JSON parse error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            postLog("❌ WebSocket error: ${t.message}")
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            postLog("WebSocket closed: $reason")
        }
    })
}
