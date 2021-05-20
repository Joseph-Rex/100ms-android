package live.hms.app2.ui.meeting

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import live.hms.app2.model.RoomDetails
import live.hms.app2.ui.meeting.chat.ChatMessage
import live.hms.app2.ui.settings.SettingsStore
import live.hms.app2.util.*
import live.hms.video.error.HMSException
import live.hms.video.media.tracks.*
import live.hms.video.sdk.HMSAudioListener
import live.hms.video.sdk.HMSSDK
import live.hms.video.sdk.HMSUpdateListener
import live.hms.video.sdk.models.*
import live.hms.video.sdk.models.enums.HMSPeerUpdate
import live.hms.video.sdk.models.enums.HMSRoomUpdate
import live.hms.video.sdk.models.enums.HMSTrackUpdate
import live.hms.video.utils.HMSCoroutineScope
import live.hms.video.utils.HMSLogger
import java.util.*
import kotlin.collections.ArrayList

class MeetingViewModel(
  application: Application,
  private val roomDetails: RoomDetails
) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "MeetingViewModel"
  }

  init {
    roomDetails.apply {
      crashlytics.setCustomKey(ROOM_ID, roomId)
      crashlytics.setCustomKey(USERNAME, username)
      crashlytics.setCustomKey(ENVIRONMENT, env)
      crashlytics.setCustomKey(AUTH_TOKEN, authToken)
    }
  }

  private val _tracks = Collections.synchronizedList(ArrayList<MeetingTrack>())

  // Flag to keep track whether the incoming audio need's to be muted
  private var _isAudioMuted = false

  // Public variable which can be accessed by views
  val isAudioMuted: Boolean
    get() = _isAudioMuted

  private val settings = SettingsStore(getApplication())

  // Live data to define the overall UI
  val state = MutableLiveData<MeetingState>(MeetingState.Disconnected())

  // TODO: Listen to changes in publishVideo & publishAudio
  //  when it is possible to switch from Audio/Video only to Audio+Video/Audio/Video/etc
  // Live data for user media controls
  val isLocalAudioEnabled = MutableLiveData(settings.publishAudio)
  val isLocalVideoEnabled = MutableLiveData(settings.publishVideo)

  private var localAudioTrack: HMSLocalAudioTrack? = null
  private var localVideoTrack: HMSLocalVideoTrack? = null

  // Live data containing all the current tracks in a meeting
  val tracks = MutableLiveData(_tracks)
  val speakers = MutableLiveData<Array<HMSSpeaker>>()

  // Dominant speaker
  val dominantSpeaker = MutableLiveData<MeetingTrack?>(null)

  val broadcastsReceived = MutableLiveData<ChatMessage>()

  private val hmsSDK = HMSSDK
    .Builder(application)
    .build()

  fun toggleLocalVideo() {
    localVideoTrack?.apply {
      val isVideo = !isMute
      setMute(isVideo)

      tracks.postValue(_tracks)

      isLocalVideoEnabled.postValue(!isVideo)
      crashlyticsLog(TAG, "toggleUserVideo: enabled=$isVideo")
    }
  }

  fun toggleLocalAudio() {
    localAudioTrack?.apply {
      val isAudio = !isMute
      setMute(isAudio)

      tracks.postValue(_tracks)

      isLocalAudioEnabled.postValue(!isAudio)
      crashlyticsLog(TAG, "toggleUserMic: enabled=$isAudio")
    }
  }

  /**
   * Helper function to toggle others audio tracks
   */
  fun toggleAudio() {
    synchronized(_tracks) {
      _isAudioMuted = !_isAudioMuted

      val volume = if (_isAudioMuted) 0.0 else 1.0
      _tracks.forEach { track ->
        if (track.audio != null && track.audio != localAudioTrack) {
          (track.audio as HMSRemoteAudioTrack).setVolume(volume)
        }
      }
    }
  }

  fun sendChatMessage(message: ChatMessage) {
    hmsSDK.sendMessage("chat", message.message)
  }

  fun startMeeting() {
    if (!(state.value is MeetingState.Disconnected || state.value is MeetingState.Failure)) {
      error("Cannot start meeting in ${state.value} state")
    }

    state.postValue(
      MeetingState.Connecting(
        "Connecting",
        "Establishing websocket connection"
      )
    )

    HMSCoroutineScope.launch {
      val info = JsonObject().apply { addProperty("name", roomDetails.username) }
      val config = HMSConfig(
        roomDetails.username,
        roomDetails.authToken,
        info.toString(),
        initEndpoint = "https://${roomDetails.env}-init.100ms.live/init"
      )
      hmsSDK.join(config, object : HMSUpdateListener {
        override fun onError(error: HMSException) {
          Log.e(TAG, error.toString())
          state.postValue(MeetingState.Failure(error))
        }

        override fun onJoin(hmsRoom: HMSRoom) {
          val peer = hmsSDK.getLocalPeer()
          peer.audioTrack?.apply {
            localAudioTrack = (this as HMSLocalAudioTrack)
            addTrack(this, peer)
          }
          peer.videoTrack?.apply {
            localVideoTrack = (this as HMSLocalVideoTrack)
            addTrack(this, peer)
          }

          state.postValue(MeetingState.Ongoing())
        }

        override fun onPeerUpdate(type: HMSPeerUpdate, peer: HMSPeer) {
          HMSLogger.d(TAG, "join:onPeerUpdate type=$type, peer=$peer")
          when (type) {
            HMSPeerUpdate.PEER_LEFT -> {
              synchronized(_tracks) {
                _tracks.removeIf { it.peer.peerID == peer.peerID }
                tracks.postValue(_tracks)
              }
            }

            HMSPeerUpdate.BECAME_DOMINANT_SPEAKER -> {
              synchronized(_tracks) {
                val prevSize = _tracks.size
                val track = _tracks.find {
                  it.peer.peerID == peer.peerID &&
                      it.video?.trackId == peer.videoTrack?.trackId
                }
                if (track != null) dominantSpeaker.postValue(track)
/*                if (track != null) {
                  dominantSpeaker.postValue(track)
                  _tracks.remove(track)
                  _tracks.add(0, track)
                  tracks.postValue(_tracks)
                }
                assert(prevSize == _tracks.size)*/
              }
            }

            HMSPeerUpdate.NO_DOMINANT_SPEAKER -> {
              dominantSpeaker.postValue(null)
            }

            else -> Unit
          }
        }


        override fun onRoomUpdate(type: HMSRoomUpdate, hmsRoom: HMSRoom) {
          HMSLogger.d(TAG, "join:onRoomUpdate type=$type, room=$hmsRoom")
        }

        override fun onTrackUpdate(type: HMSTrackUpdate, track: HMSTrack, peer: HMSPeer) {
          HMSLogger.d(TAG, "join:onTrackUpdate type=$type, track=$track, peer=$peer")
          when (type) {
            HMSTrackUpdate.TRACK_ADDED -> addTrack(track, peer)
            HMSTrackUpdate.TRACK_REMOVED -> {
              if (track.type == HMSTrackType.VIDEO) removeTrack(track as HMSVideoTrack, peer)
            }
            HMSTrackUpdate.TRACK_MUTED -> tracks.postValue(_tracks)
            HMSTrackUpdate.TRACK_UNMUTED -> tracks.postValue(_tracks)
            HMSTrackUpdate.TRACK_DESCRIPTION_CHANGED -> tracks.postValue(_tracks)
          }
        }

        override fun onMessageReceived(message: HMSMessage) {
          Log.v(TAG, "onMessageReceived: $message")
          broadcastsReceived.postValue(
            ChatMessage(
              message.sender.name,
              message.time,
              message.message,
              false
            )
          )
        }
      })

      hmsSDK.addAudioObserver(object : HMSAudioListener {
        override fun onAudioLevelUpdate(speakers: Array<HMSSpeaker>) {
          Log.v(TAG, "onAudioLevelUpdate: speakers=$speakers")
          this@MeetingViewModel.speakers.postValue(speakers)
        }
      })
    }
  }


  fun flipCamera() {
    if (!settings.publishVideo) {
      error("Cannot switch camera when Video is disabled")
    }

    // NOTE: During audio-only calls, this switch-camera is ignored
    //  as no camera in use
    HMSCoroutineScope.launch {
      localVideoTrack!!.switchCamera()
    }
  }

  fun leaveMeeting() {
    state.postValue(MeetingState.Disconnecting("Disconnecting", "Leaving meeting"))
    hmsSDK.leave()
    state.postValue(MeetingState.Disconnected(true))
  }

  private fun addAudioTrack(track: HMSAudioTrack, peer: HMSPeer) {
    synchronized(_tracks) {
      // Check if this track already exists
      val _track = _tracks.find {
        it.audio == null &&
            it.peer.peerID == peer.peerID &&
            it.isScreen.not()
      }

      if (_track == null) {
        if (peer.isLocal) {
          _tracks.add(0, MeetingTrack(peer, null, track))
        } else {
          _tracks.add(MeetingTrack(peer, null, track))
        }
      } else {
        _track.audio = track
      }

      tracks.postValue(_tracks)
    }
  }


  private fun addVideoTrack(track: HMSVideoTrack, peer: HMSPeer) {
    synchronized(_tracks) {
      // Check if this track already exists
      val _track = _tracks.find { it.video == null && it.peer.peerID == peer.peerID }
      if (_track == null) {
        if (peer.isLocal) {
          _tracks.add(0, MeetingTrack(peer, track, null))
        } else {
          _tracks.add(MeetingTrack(peer, track, null))
        }
      } else {
        _track.video = track
      }
    }

    tracks.postValue(_tracks)
  }

  private fun addTrack(track: HMSTrack, peer: HMSPeer) {
    if (track is HMSAudioTrack) addAudioTrack(track, peer)
    else if (track is HMSVideoTrack) addVideoTrack(track, peer)

    HMSLogger.v(TAG, "addTrack: count=${_tracks.size} track=$track, peer=$peer")
  }

  private fun removeTrack(track: HMSVideoTrack, peer: HMSPeer) {
    synchronized(_tracks) {
      val trackToRemove = _tracks.find {
        it.peer.peerID == peer.peerID &&
            it.video?.trackId == track.trackId
      }
      _tracks.remove(trackToRemove)

      // Update the view as we have removed some views
      tracks.postValue(_tracks)
    }
  }

  private fun getLocalScreen() {
    state.postValue(
      MeetingState.LoadingMedia(
        "Loading Media",
        "Getting user local stream"
      )
    )

    // onConnect -> Join -> getUserMedia
  }
}
