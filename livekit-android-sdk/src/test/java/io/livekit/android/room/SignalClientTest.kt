/*
 * Copyright 2023-2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.livekit.android.room

import io.livekit.android.BaseTest
import io.livekit.android.mock.MockWebSocketFactory
import io.livekit.android.mock.TestData
import io.livekit.android.stats.NetworkInfo
import io.livekit.android.stats.NetworkType
import io.livekit.android.util.toOkioByteString
import io.livekit.android.util.toPBByteString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitModels.ClientConfiguration
import livekit.LivekitRtc
import livekit.LivekitRtc.ICEServer
import livekit.org.webrtc.SessionDescription
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times

@ExperimentalCoroutinesApi
class SignalClientTest : BaseTest() {

    lateinit var wsFactory: MockWebSocketFactory
    lateinit var client: SignalClient

    @Mock
    lateinit var listener: SignalClient.Listener

    @Mock
    lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        wsFactory = MockWebSocketFactory()
        client = SignalClient(
            wsFactory,
            Json,
            okHttpClient = okHttpClient,
            ioDispatcher = coroutineRule.dispatcher,
            networkInfo = object : NetworkInfo {
                override fun getNetworkType() = NetworkType.WIFI
            },
        )
        client.listener = listener
    }

    private fun createOpenResponse(request: Request): Response {
        return Response.Builder()
            .request(request)
            .code(200)
            .protocol(Protocol.HTTP_2)
            .message("")
            .build()
    }

    /**
     * Supply the needed websocket messages to finish a join call.
     */
    private fun connectWebsocketAndJoin() {
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, JOIN.toOkioByteString())
    }

    @Test
    fun joinAndResponse() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()

        val response = job.await()
        assertEquals(true, client.isConnected)
        assertEquals(response, JOIN.join)
    }

    @Test
    fun reconnect() = runTest {
        val job = async {
            client.reconnect(EXAMPLE_URL, "", "participant_sid")
        }

        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, RECONNECT.toOkioByteString())

        job.await()
        assertEquals(true, client.isConnected)
    }

    @Test
    fun joinFailure() = runTest {
        var failed = false
        val job = async {
            try {
                client.join(EXAMPLE_URL, "")
            } catch (e: Exception) {
                failed = true
            }
        }

        client.onFailure(wsFactory.ws, Exception(), null)
        job.await()

        assertTrue(failed)
    }

    @Test
    fun listenerNotCalledUntilOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }

        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()

        Mockito.verifyNoInteractions(listener)
    }

    @Test
    fun listenerCalledAfterOnReady() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())

        job.await()
        client.onReadyForResponses()
        Mockito.verify(listener)
            .onOffer(argThat { type == SessionDescription.Type.OFFER && description == OFFER.offer.sdp })
    }

    /**
     * [WebSocketListener.onFailure] does not call through to
     * [WebSocketListener.onClosed]. Ensure that listener is called properly.
     */
    @Test
    fun listenerNotifiedAfterFailure() = runTest {
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        job.await()

        client.onFailure(wsFactory.ws, Exception(), null)

        Mockito.verify(listener)
            .onClose(any(), any())
    }

    /**
     * Ensure responses that come in before [SignalClient.onReadyForResponses] are queued.
     */
    @Test
    fun queuedResponses() = runTest {
        val inOrder = inOrder(listener)
        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        connectWebsocketAndJoin()
        job.await()

        client.onMessage(wsFactory.ws, OFFER.toOkioByteString())
        client.onMessage(wsFactory.ws, ROOM_UPDATE.toOkioByteString())
        client.onMessage(wsFactory.ws, ROOM_UPDATE.toOkioByteString())

        client.onReadyForResponses()

        inOrder.verify(listener).onOffer(any())
        inOrder.verify(listener, times(2)).onRoomUpdate(any())
    }

    @Test
    fun sendRequest() = runTest {
        val job = async { client.join(EXAMPLE_URL, "") }
        connectWebsocketAndJoin()
        job.await()

        client.sendMuteTrack("sid", true)

        val ws = wsFactory.ws

        assertEquals(1, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun queuedRequests() = runTest {
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)

        val job = async { client.join(EXAMPLE_URL, "") }
        connectWebsocketAndJoin()
        job.await()

        val ws = wsFactory.ws
        assertEquals(3, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun queuedRequestsWhileReconnecting() = runTest {
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)
        client.sendMuteTrack("sid", true)

        val job = async { client.reconnect(EXAMPLE_URL, "", "participant_sid") }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, RECONNECT.toOkioByteString())
        job.await()

        val ws = wsFactory.ws

        // Wait until peer connection is connected to send requests.
        assertEquals(0, ws.sentRequests.size)

        client.onPCConnected()

        assertEquals(3, ws.sentRequests.size)
        val sentRequest = LivekitRtc.SignalRequest.newBuilder()
            .mergeFrom(ws.sentRequests[0].toPBByteString())
            .build()

        assertTrue(sentRequest.hasMute())
    }

    @Test
    fun pingTest() = runTest {
        val joinResponseWithPing = with(JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                pingInterval = 10
                pingTimeout = 20
                build()
            }
            build()
        }

        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, joinResponseWithPing.toOkioByteString())
        job.await()
        val originalWs = wsFactory.ws
        assertFalse(originalWs.isClosed)

        testScheduler.advanceTimeBy(15 * 1000)
        assertTrue(
            originalWs.sentRequests.any { requestString ->
                val sentRequest = LivekitRtc.SignalRequest.newBuilder()
                    .mergeFrom(requestString.toPBByteString())
                    .build()

                return@any sentRequest.hasPing()
            },
        )

        client.onMessage(wsFactory.ws, PONG.toOkioByteString())

        testScheduler.advanceTimeBy(10 * 1000)
        assertFalse(originalWs.isClosed)
    }

    @Test
    fun pingTimeoutTest() = runTest {
        val joinResponseWithPing = with(JOIN.toBuilder()) {
            join = with(join.toBuilder()) {
                pingInterval = 10
                pingTimeout = 20
                build()
            }
            build()
        }

        val job = async {
            client.join(EXAMPLE_URL, "")
        }
        client.onOpen(wsFactory.ws, createOpenResponse(wsFactory.request))
        client.onMessage(wsFactory.ws, joinResponseWithPing.toOkioByteString())
        job.await()
        val originalWs = wsFactory.ws
        assertFalse(originalWs.isClosed)

        testScheduler.advanceUntilIdle()

        assertTrue(originalWs.isClosed)
    }

    // mock data
    companion object {
        const val EXAMPLE_URL = "ws://www.example.com"

        val JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
            join = with(LivekitRtc.JoinResponse.newBuilder()) {
                room = with(LivekitModels.Room.newBuilder()) {
                    name = "roomname"
                    build()
                }
                participant = TestData.LOCAL_PARTICIPANT
                subscriberPrimary = true
                addIceServers(
                    with(ICEServer.newBuilder()) {
                        addUrls("stun:stun.join.com:19302")
                        username = "username"
                        credential = "credential"
                        build()
                    },
                )
                serverVersion = "0.15.2"
                build()
            }
            build()
        }

        val RECONNECT = with(LivekitRtc.SignalResponse.newBuilder()) {
            reconnect = with(LivekitRtc.ReconnectResponse.newBuilder()) {
                addIceServers(
                    with(ICEServer.newBuilder()) {
                        addUrls("stun:stun.reconnect.com:19302")
                        username = "username"
                        credential = "credential"
                        build()
                    },
                )
                clientConfiguration = with(ClientConfiguration.newBuilder()) {
                    forceRelay = LivekitModels.ClientConfigSetting.ENABLED
                    build()
                }
                build()
            }
            build()
        }

        val OFFER = with(LivekitRtc.SignalResponse.newBuilder()) {
            offer = with(LivekitRtc.SessionDescription.newBuilder()) {
                sdp = "remote_offer"
                type = "offer"
                build()
            }
            build()
        }

        val ROOM_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            roomUpdate = with(LivekitRtc.RoomUpdate.newBuilder()) {
                room = with(LivekitModels.Room.newBuilder()) {
                    sid = "room_sid"
                    metadata = "metadata"
                    activeRecording = true
                    build()
                }
                build()
            }
            build()
        }

        val LOCAL_TRACK_PUBLISHED = with(LivekitRtc.SignalResponse.newBuilder()) {
            trackPublished = with(LivekitRtc.TrackPublishedResponse.newBuilder()) {
                cid = "local_cid"
                track = TestData.LOCAL_AUDIO_TRACK
                build()
            }
            build()
        }

        val LOCAL_TRACK_UNPUBLISHED = with(LivekitRtc.SignalResponse.newBuilder()) {
            trackUnpublished = with(LivekitRtc.TrackUnpublishedResponse.newBuilder()) {
                trackSid = TestData.LOCAL_AUDIO_TRACK.sid
                build()
            }
            build()
        }

        val PERMISSION_CHANGE = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                addParticipants(
                    TestData.LOCAL_PARTICIPANT.toBuilder()
                        .setPermission(
                            LivekitModels.ParticipantPermission.newBuilder()
                                .setCanPublish(false)
                                .setCanSubscribe(false)
                                .setCanPublishData(false)
                                .setHidden(false)
                                .setRecorder(false)
                                .build(),
                        )
                        .build(),
                )
                build()
            }
            build()
        }

        val PARTICIPANT_JOIN = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                addParticipants(TestData.REMOTE_PARTICIPANT)
                build()
            }
            build()
        }

        val PARTICIPANT_DISCONNECT = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val disconnectedParticipant = TestData.REMOTE_PARTICIPANT.toBuilder()
                    .setState(LivekitModels.ParticipantInfo.State.DISCONNECTED)
                    .build()

                addParticipants(disconnectedParticipant)
                build()
            }
            build()
        }

        val ACTIVE_SPEAKER_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            speakersChanged = with(LivekitRtc.SpeakersChanged.newBuilder()) {
                addSpeakers(TestData.REMOTE_SPEAKER_INFO)
                build()
            }
            build()
        }

        val LOCAL_PARTICIPANT_METADATA_CHANGED = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val participantMetadataChanged = TestData.LOCAL_PARTICIPANT.toBuilder()
                    .setMetadata("changed_metadata")
                    .setName("changed_name")
                    .build()

                addParticipants(participantMetadataChanged)
                build()
            }
            build()
        }

        val REMOTE_PARTICIPANT_METADATA_CHANGED = with(LivekitRtc.SignalResponse.newBuilder()) {
            update = with(LivekitRtc.ParticipantUpdate.newBuilder()) {
                val participantMetadataChanged = TestData.REMOTE_PARTICIPANT.toBuilder()
                    .setMetadata("changed_metadata")
                    .setName("changed_name")
                    .build()

                addParticipants(participantMetadataChanged)
                build()
            }
            build()
        }

        val CONNECTION_QUALITY = with(LivekitRtc.SignalResponse.newBuilder()) {
            connectionQuality = with(LivekitRtc.ConnectionQualityUpdate.newBuilder()) {
                addUpdates(
                    with(LivekitRtc.ConnectionQualityInfo.newBuilder()) {
                        participantSid = JOIN.join.participant.sid
                        quality = LivekitModels.ConnectionQuality.EXCELLENT
                        build()
                    },
                )
                build()
            }
            build()
        }

        val STREAM_STATE_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            streamStateUpdate = with(LivekitRtc.StreamStateUpdate.newBuilder()) {
                addStreamStates(
                    with(LivekitRtc.StreamStateInfo.newBuilder()) {
                        participantSid = TestData.REMOTE_PARTICIPANT.sid
                        trackSid = TestData.REMOTE_AUDIO_TRACK.sid
                        state = LivekitRtc.StreamState.ACTIVE
                        build()
                    },
                )
                build()
            }
            build()
        }

        val SUBSCRIPTION_PERMISSION_UPDATE = with(LivekitRtc.SignalResponse.newBuilder()) {
            subscriptionPermissionUpdate = with(LivekitRtc.SubscriptionPermissionUpdate.newBuilder()) {
                participantSid = TestData.REMOTE_PARTICIPANT.sid
                trackSid = TestData.REMOTE_AUDIO_TRACK.sid
                allowed = false
                build()
            }
            build()
        }

        val REFRESH_TOKEN = with(LivekitRtc.SignalResponse.newBuilder()) {
            refreshToken = "refresh_token"
            build()
        }

        val PONG = with(LivekitRtc.SignalResponse.newBuilder()) {
            pong = 1L
            build()
        }

        val LEAVE = with(LivekitRtc.SignalResponse.newBuilder()) {
            leave = with(LivekitRtc.LeaveRequest.newBuilder()) {
                reason = LivekitModels.DisconnectReason.SERVER_SHUTDOWN
                build()
            }
            build()
        }
    }
}
