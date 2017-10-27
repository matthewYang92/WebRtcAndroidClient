package yang.webrtcdataclient;

import android.util.Log;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * author: Matthew Yang on 17/10/26
 * e-mail: yangtian@yy.com
 */

public class WebRtcClient {

    private final static String TAG = "WebRtcClient";
    private final static String mSocketAddress = "http://172.25.64.1:3000/";

    private PeerConnectionFactory factory;
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private Socket client;
    private String mClientId;
    private Map<String, Peer> peers = new HashMap<>();
    private MediaConstraints constraints = new MediaConstraints();
    private WebRtcListener webRtcListener;

    private Emitter.Listener messageListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject data = (JSONObject) args[0];
            Log.d(TAG, "messageListener call data : " + data);
            try {
                String from = data.getString("from");
                String type = data.getString("type");
                JSONObject payload = null;
                if (!type.equals("init")) {
                    payload = data.getJSONObject("payload");
                }
                switch (type) {
                    case "init":
                        onReceiveInit(from);
                        break;
                    case "offer":
                        onReceiveOffer(from, payload);
                        break;
                    case "answer":
                        onReceiveAnswer(from, payload);
                        break;
                    case "candidate":
                        onReceiveCandidate(from, payload);
                        break;
                    default:
                        break;
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    };

    private Emitter.Listener clientIdListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            mClientId = (String) args[0];
            Log.d(TAG, "clientIdListener call data : " + mClientId);
        }
    };

    public WebRtcClient() {
        factory = new PeerConnectionFactory(new PeerConnectionFactory.Options());

        try {
            client = IO.socket(mSocketAddress);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        client.on("message", messageListener);
        client.on("id", clientIdListener);
        client.connect();

        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
    }

    public void setWebRtcListener(WebRtcListener webRtcListener) {
        this.webRtcListener = webRtcListener;
    }

    /**
     * 向信令服务器发送init
     */
    public void sendInitMessage() {
        client.emit("init");
    }

    /**
     * 向信令服务器发消息
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    public void sendMessage(String to, String type, JSONObject payload) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("to", to);
        message.put("type", type);
        message.put("payload", payload);
        message.put("from", mClientId);
        client.emit("message", message);
    }

    /**
     * 向所有连接的peer端发送消息
     *
     * @param message
     */
    public void sendDataMessageToAllPeer(String message) {
        for (Peer peer : peers.values()) {
            peer.sendDataChannelMessage(message);
        }
    }

    private Peer getPeer(String from) {
        Peer peer;
        if (!peers.containsKey(from)) {
            peer = addPeer(from);
        } else {
            peer = peers.get(from);
        }
        return peer;
    }

    private Peer addPeer(String id) {
        Peer peer = new Peer(id);
        peers.put(id, peer);
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        peer.release();
        peers.remove(peer.id);
    }

    public void onReceiveInit(String fromUid) {
        Log.d(TAG, "onReceiveInit fromUid:" + fromUid);
        Peer peer = getPeer(fromUid);
        peer.pc.createOffer(peer, constraints);
    }

    public void onReceiveOffer(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveOffer uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, constraints);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void onReceiveAnswer(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveAnswer uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void onReceiveCandidate(String fromUid, JSONObject payload) {
        Log.d(TAG, "onReceiveCandidate uid:" + fromUid + " data:" + payload);
        try {
            Peer peer = getPeer(fromUid);
            if (peer.pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.getString("id"),
                        payload.getInt("label"),
                        payload.getString("candidate")
                );
                peer.pc.addIceCandidate(candidate);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void release() {
        for (Peer peer : peers.values()) {
            peer.release();
        }
        factory.dispose();
        client.disconnect();
        client.close();
    }

    public class Peer implements SdpObserver, PeerConnection.Observer, DataChannel.Observer {
        PeerConnection pc;
        String id;
        DataChannel dc;

        public Peer(String id) {
            Log.d(TAG, "new Peer: " + id);
            this.pc = factory.createPeerConnection(
                    iceServers, //ICE服务器列表
                    constraints, //MediaConstraints
                    this); //Context
            this.id = id;

            /*
            DataChannel.Init 可配参数说明：
            ordered：是否保证顺序传输；
            maxRetransmitTimeMs：重传允许的最长时间；
            maxRetransmits：重传允许的最大次数；
             */
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            dc = pc.createDataChannel("dataChannel", init);
        }

        public void sendDataChannelMessage(String message) {
            byte[] msg = message.getBytes();
            DataChannel.Buffer buffer = new DataChannel.Buffer(
                    ByteBuffer.wrap(msg),
                    false);
            dc.send(buffer);
        }

        public void release() {
            pc.dispose();
            dc.close();
            dc.dispose();
        }

        //SdpObserver-------------------------------------------------------------------------------

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            Log.d(TAG, "onCreateSuccess: " + sdp.description);
            try {
                JSONObject payload = new JSONObject();
                payload.put("type", sdp.type.canonicalForm());
                payload.put("sdp", sdp.description);
                sendMessage(id, sdp.type.canonicalForm(), payload);
                pc.setLocalDescription(Peer.this, sdp);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }

        //DataChannel.Observer----------------------------------------------------------------------

        @Override
        public void onBufferedAmountChange(long l) {

        }

        @Override
        public void onStateChange() {
            Log.d(TAG, "onDataChannel onStateChange:" + dc.state());
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            Log.d(TAG, "onDataChannel onMessage : " + buffer);
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.capacity()];
            data.get(bytes);
            String msg = new String(bytes);
            if (webRtcListener != null) {
                webRtcListener.onReceiveDataChannelMessage(msg);
            }
        }

        //PeerConnection.Observer-------------------------------------------------------------------

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "onIceConnectionChange : " + iceConnectionState.name());
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id);
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            try {
                JSONObject payload = new JSONObject();
                payload.put("label", candidate.sdpMLineIndex);
                payload.put("id", candidate.sdpMid);
                payload.put("candidate", candidate.sdp);
                sendMessage(id, "candidate", payload);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {

        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "onDataChannel label:" + dataChannel.label());
            dataChannel.registerObserver(this);
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }
    }

    public interface WebRtcListener {
        void onReceiveDataChannelMessage(String message);
    }
}
